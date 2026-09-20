package ltd.pepper.lib.world;

import java.util.Optional;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * 安全落点搜索：在给定区域的**四条边外侧**找一处可站立、且通过准入判定的位置。
 *
 * <p>移植自 Residence 6.0.3.3 {@code LocationUtil#getOutsideFreeLoc / #getEdgeLocation /
 * #getRandomEdge / #isValidLocation / #fallBackLocation}（LocationUtil 492 行），语义逐条对齐。</p>
 *
 * <h2>取点顺序（{@code getRandomEdge}，按 {@code iteration % 4} 轮转四条边）</h2>
 * <pre>
 *   it%4==0 → x = minX - 1, z = minZ - 1 + rand(sizeZ + 2)   // 西边，整体外扩 1 格
 *   it%4==1 → x = maxX + 1, z = minZ - 1 + rand(sizeZ + 2)   // 东边
 *   it%4==2 → x = minX + rand(sizeX), z = minZ - 1           // 北边
 *   it%4==3 → x = minX + rand(sizeX), z = maxZ + 1           // 南边
 * </pre>
 * <p>即「按边轮转 + 边上随机」，不是纯随机撒点——这一点影响命中率与可复现性，故照搬。</p>
 *
 * <h2>落点判定（{@code isValidLocation}）</h2>
 * <p>官方要求：目标格**无碰撞箱**、其上方一格**无碰撞箱**、其下方一格**非空且非岩浆**。
 * 官方的「空」是 {@code CMIMC.NOCOLLISIONBOX}（无碰撞箱），比 {@code isAir()} 宽——
 * 水、草、火把都算空。此处用 {@link BlockReader#passable} 表达，生产实现走
 * {@code Block#isPassable()}。</p>
 *
 * <h2>纵向扫描（{@code getEdgeLocation} 的 for 循环）</h2>
 * <p>从区域**顶部**向下扫到区域**底部**，取**最先**满足落点判定的 Y（即最上面那处）；
 * 若在下扫过程中先撞到实体方块（说明该 XZ 的地表高于区域顶部，人会被埋进去），
 * 本次尝试判失败。等价于：区域内的最高非空方块若正好在顶部 → 失败；否则落点 = 它上方一格。</p>
 *
 * <h2>与官方的两处有意差异</h2>
 * <ol>
 *   <li><b>同步执行</b>：官方走 {@code getSnapshot(...)} + 异步调度（区块快照 + 主线程回调），
 *       目的是不卡主线程。本类为**同步**实现——调用方是传送/踢出这类低频动作，尝试次数有界
 *       （默认 15 次），且每次扫描在正常地形下只走到地表即停。若将来用在每 tick 路径上，
 *       调用方需自行切到异步线程并只把落点应用回主线程。</li>
 *   <li><b>不做出生点回退</b>：官方 {@code fallBackLocation} 会在搜不到时回退到配置的
 *       {@code KickLocation} 或世界出生点。这是**策略**而非搜索，故留在调用方，
 *       由本类返回 {@link Optional#empty()} 表达「没找到」。</li>
 * </ol>
 *
 * <p>官方在找到落点后还会做一次「目标领地 {@code tp}/{@code move} 权限检查」——那是领地插件的
 * 语义，通过 {@link TargetFilter} 注入：返回 {@code false} 即放弃该点并继续下一次尝试。</p>
 */
public final class SafeLandingSearch {

    /** 默认尝试次数（官方 {@code maxIt = 15}）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 15;

    private static final Random SHARED_RANDOM = new Random();

    private SafeLandingSearch() {}

    /**
     * 搜索区域（**方块坐标**，闭区间）。
     *
     * <p>官方用 {@code CuboidArea}；此处不依赖任何领地模型，只收一组边界，便于复用。</p>
     */
    public record Region(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public Region {
            if (world == null) {
                throw new IllegalArgumentException("world");
            }
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("region bounds must be ordered");
            }
        }

        /** 官方 {@code CuboidArea#getXSize()}：闭区间宽度。 */
        public int sizeX() {
            return this.maxX - this.minX + 1;
        }

        /** 官方 {@code CuboidArea#getZSize()}：闭区间深度。 */
        public int sizeZ() {
            return this.maxZ - this.minZ + 1;
        }
    }

    /**
     * 方块读取口——把 {@code World} 的方块查询抽象出来，使搜索逻辑可在无服务端环境下测试。
     *
     * <p>生产用 {@link #of(World)}。</p>
     */
    public interface BlockReader {

        /** 该位置是否**无碰撞箱**（官方 {@code CMIMC.NOCOLLISIONBOX}）。 */
        boolean passable(int x, int y, int z);

        /**
         * 该位置是否为**空气**（官方 {@code Block#isEmpty()}，即 {@code isAir} 语义）。
         *
         * <p>注意与 {@link #passable} 的区别：后者是「无碰撞箱」（水、草、火把也算），
         * 前者是「就是空气」。官方的边缘搜索用前者、纵向搜索用后者，故两个都要。</p>
         */
        boolean isEmpty(int x, int y, int z);

        /** 该位置的方块类型。 */
        Material typeAt(int x, int y, int z);

        /** 生产实现：直接读 {@link World}。 */
        static BlockReader of(final World world) {
            return new BlockReader() {
                @Override
                public boolean passable(final int x, final int y, final int z) {
                    return world.getBlockAt(x, y, z).isPassable();
                }

                @Override
                public boolean isEmpty(final int x, final int y, final int z) {
                    return world.getBlockAt(x, y, z).isEmpty();
                }

                @Override
                public Material typeAt(final int x, final int y, final int z) {
                    return world.getBlockAt(x, y, z).getType();
                }
            };
        }
    }

    /**
     * 落点准入判定：返回 {@code false} 即放弃该点、继续下一次尝试。
     *
     * <p>领地插件在此检查目标位置所属领地的 {@code tp}/{@code move} 权限（官方在
     * {@code getEdgeLocation} 里对目标领地做同样的事）。</p>
     */
    @FunctionalInterface
    public interface TargetFilter {
        boolean accepts(Location candidate);
    }

    /** 搜索参数。 */
    public record Options(int maxAttempts) {

        public Options {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
        }

        /** 官方默认：15 次。 */
        public static Options defaults() {
            return new Options(DEFAULT_MAX_ATTEMPTS);
        }
    }

    /** 用默认参数搜索（15 次尝试、共享随机源）。 */
    public static Optional<Location> findOutside(
            final Region region, final BlockReader reader, final TargetFilter filter) {
        return findOutside(region, reader, filter, Options.defaults(), SHARED_RANDOM);
    }

    /**
     * 在区域四条边外侧搜索可站立位置。
     *
     * @param region  搜索区域
     * @param reader  方块读取口
     * @param filter  落点准入（可为 {@code null}，表示不做额外校验）
     * @param options 参数
     * @param random  随机源（可注入以便测试复现）
     * @return 找到的位置（已按官方 {@code loc.add(0.5, 0.1, 0.5)} 居中微抬）；未找到则空
     */
    public static Optional<Location> findOutside(
            final Region region,
            final BlockReader reader,
            final TargetFilter filter,
            final Options options,
            final Random random) {
        for (int iteration = 1; iteration <= options.maxAttempts(); iteration++) {
            final int[] xz = edgePoint(region, iteration, random);
            final Integer standingY = highestStandingY(region, reader, xz[0], xz[1]);
            if (standingY == null) {
                continue;
            }
            // 官方落点：方块中心 + 0.5、Y 抬 0.1（避免卡在方块边沿）。
            final Location candidate =
                    new Location(region.world(), xz[0] + 0.5, standingY + 0.1, xz[1] + 0.5);
            if (filter == null || filter.accepts(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * 官方 {@code getRandomEdge}：按 {@code iteration % 4} 轮转四条边，并在该边上随机取一点，
     * 整体**外扩 1 格**。返回 {@code {x, z}}。
     */
    static int[] edgePoint(final Region region, final int iteration, final Random random) {
        final int xSize = region.sizeX();
        final int zSize = region.sizeZ();
        return switch (iteration % 4) {
            case 0 -> new int[] {region.minX() - 1, region.minZ() - 1 + random.nextInt(zSize + 2)};
            case 1 -> new int[] {region.maxX() + 1, region.minZ() - 1 + random.nextInt(zSize + 2)};
            case 2 -> new int[] {region.minX() + random.nextInt(xSize), region.minZ() - 1};
            default -> new int[] {region.minX() + random.nextInt(xSize), region.maxZ() + 1};
        };
    }

    /**
     * 从区域顶部向下找**最上面**的可站立 Y；若先撞到实体方块（地表高于区域顶部）则返回
     * {@code null}（官方该次尝试判失败）。
     */
    private static Integer highestStandingY(
            final Region region, final BlockReader reader, final int x, final int z) {
        for (int y = region.maxY(); y > region.minY(); y--) {
            if (isSafeStandingAt(reader, x, y, z)) {
                return y;
            }
            if (!reader.passable(x, y, z)) {
                return null;
            }
        }
        return null;
    }

    /**
     * 同一 XZ 上**就近**找可站立点——官方 {@code ResidencePlayerListener#getSafeLocation}
     * （RPL:1605-1628），用于 elytra 强制收伞后的救援落地。
     *
     * <p>判据：某格**非空气**且其上方两格**都是空气** → 该格上方一格即落点。先**向下**扫
     * （从 {@code origin} 的 Y 直到 {@code minY}），再**向上**扫（直到 {@code maxY}）。</p>
     *
     * <p>与 {@link #findOutside} 的区别：那个在区域四条边外侧找，这个在同一竖列里找。
     * 另外官方此处的「空」用的是 {@code Block#isEmpty()}（就是空气），不是
     * {@code NOCOLLISIONBOX}；且**不排除岩浆**（官方原样如此，照搬）。</p>
     *
     * <p>官方下扫下界硬编码为 {@code 0}、上扫上界为 {@code World#getMaxHeight()}；此处改用调用方
     * 传入的 {@code minY}/{@code maxY}（生产传世界的实际边界），使 y&lt;0 的地层也能被搜到——
     * 这是有意的修正，不影响常见地形下的结果。</p>
     *
     * @return 落点（保留原 XZ 的小数部分与朝向）；找不到则空
     */
    public static Optional<Location> safeColumnAt(
            final Location origin, final BlockReader reader, final int minY, final int maxY) {
        if (origin == null || reader == null) {
            return Optional.empty();
        }
        final int x = origin.getBlockX();
        final int z = origin.getBlockZ();
        final int curY = origin.getBlockY();

        for (int y = curY; y >= minY; y--) {
            if (isStandableColumn(reader, x, y, z)) {
                return Optional.of(withY(origin, y + 1));
            }
        }
        for (int y = curY + 1; y <= maxY; y++) {
            if (isStandableColumn(reader, x, y, z)) {
                return Optional.of(withY(origin, y + 1));
            }
        }
        return Optional.empty();
    }

    /** 该格非空气 + 上方两格都是空气（官方 {@code getSafeLocation} 的判据）。 */
    private static boolean isStandableColumn(
            final BlockReader reader, final int x, final int y, final int z) {
        return !reader.isEmpty(x, y, z) && reader.isEmpty(x, y + 1, z) && reader.isEmpty(x, y + 2, z);
    }

    private static Location withY(final Location origin, final int y) {
        final Location copy = origin.clone();
        copy.setY(y);
        return copy;
    }

    /**
     * 官方 {@code isValidLocation}：该格无碰撞箱 + 上方一格无碰撞箱 + 下方一格非空且非岩浆。
     */
    public static boolean isSafeStandingAt(
            final BlockReader reader, final int x, final int y, final int z) {
        if (!reader.passable(x, y, z) || !reader.passable(x, y + 1, z)) {
            return false;
        }
        if (reader.passable(x, y - 1, z)) {
            return false;
        }
        return reader.typeAt(x, y - 1, z) != Material.LAVA;
    }
}