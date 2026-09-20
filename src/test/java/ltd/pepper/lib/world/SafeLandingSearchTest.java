package ltd.pepper.lib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * {@link SafeLandingSearch} 语义测试——逐条对齐官方
 * {@code LocationUtil#getEdgeLocation / #getRandomEdge / #isValidLocation}。
 */
class SafeLandingSearchTest {

    /**
     * World mock **强持有**。
     *
     * <p>Paper 26.x 的 {@code Location} 用 {@code WeakReference} 持有 world，
     * {@code Location.getWorld()} 在引用被 GC 后抛 {@code World unloaded}。
     * 若只在辅助方法里临时 mock 而仅由 Location 引用，测试会随 GC 时机时红时绿。</p>
     */
    private final World world = mockWorld();

    private static World mockWorld() {
        final World w = mock(World.class);
        when(w.getName()).thenReturn("world");
        return w;
    }

    private SafeLandingSearch.Region region() {
        return new SafeLandingSearch.Region(this.world, 0, 0, 0, 15, 200, 15);
    }

    /**
     * 高度图方块读取口：{@code y > surfaceY} 为可穿过（空气），其余为实体地面。
     * {@code lavaColumn} 指定的 XZ 地面改为岩浆（用于验证「下方非岩浆」）。
     */
    private static SafeLandingSearch.BlockReader flat(final int surfaceY, final Material ground) {
        return new SafeLandingSearch.BlockReader() {
            @Override
            public boolean passable(final int x, final int y, final int z) {
                return y > surfaceY;
            }

            @Override
            public boolean isEmpty(final int x, final int y, final int z) {
                return y > surfaceY;
            }

            @Override
            public Material typeAt(final int x, final int y, final int z) {
                return y > surfaceY ? Material.AIR : ground;
            }
        };
    }

    // ------------------------------------------------------------------
    // 取点：官方 getRandomEdge 的四边轮转
    // ------------------------------------------------------------------

    /** 官方按 {@code it % 4} 轮转四条边，每条边随机一点，整体外扩 1 格。 */
    @Test
    void edgePointCyclesAllFourEdges() {
        final SafeLandingSearch.Region r = region();
        final Random random = new Random(1234L);

        for (int it = 1; it <= 8; it++) {
            final int[] p = SafeLandingSearch.edgePoint(r, it, random);
            switch (it % 4) {
                case 0 -> {
                    assertEquals(-1, p[0], "西边：x = minX - 1");
                    assertTrue(p[1] >= -1 && p[1] <= 16, "西边：z 在 [minZ-1, maxZ+1]");
                }
                case 1 -> {
                    assertEquals(16, p[0], "东边：x = maxX + 1");
                    assertTrue(p[1] >= -1 && p[1] <= 16);
                }
                case 2 -> {
                    assertTrue(p[0] >= 0 && p[0] <= 15, "北边：x 在区域内");
                    assertEquals(-1, p[1], "北边：z = minZ - 1");
                }
                default -> {
                    assertTrue(p[0] >= 0 && p[0] <= 15);
                    assertEquals(16, p[1], "南边：z = maxZ + 1");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 落点判定：官方 isValidLocation
    // ------------------------------------------------------------------

    /** 该格空 + 上方空 + 下方实体非岩浆 → 可站立。 */
    @Test
    void safeStandingRequiresAirAboveAndSolidBelow() {
        final SafeLandingSearch.BlockReader reader = flat(64, Material.STONE);
        assertTrue(SafeLandingSearch.isSafeStandingAt(reader, 3, 65, 3), "地面上方一格可站立");
    }

    /** 下方是岩浆 → 不安全（官方显式排除 LAVA）。 */
    @Test
    void lavaBelowIsNotSafe() {
        final SafeLandingSearch.BlockReader reader = flat(64, Material.LAVA);
        assertFalse(SafeLandingSearch.isSafeStandingAt(reader, 3, 65, 3), "岩浆上方不可站立");
    }

    /** 下方是空的（悬空）→ 不安全。 */
    @Test
    void airBelowIsNotSafe() {
        final SafeLandingSearch.BlockReader reader = flat(64, Material.STONE);
        assertFalse(SafeLandingSearch.isSafeStandingAt(reader, 3, 70, 3), "脚下悬空不可站立");
    }

    /** 该格本身是实体 → 不安全（人会被埋）。 */
    @Test
    void solidAtTargetIsNotSafe() {
        final SafeLandingSearch.BlockReader reader = flat(64, Material.STONE);
        assertFalse(SafeLandingSearch.isSafeStandingAt(reader, 3, 64, 3), "实体方块内不可站立");
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    /** 平地：应在某条边外扩 1 格、地表上方 1 格找到落点。 */
    @Test
    void findOutsideReturnsSpotOnEdgeAboveSurface() {
        final Optional<Location> found = SafeLandingSearch.findOutside(
                region(), flat(64, Material.STONE), null, SafeLandingSearch.Options.defaults(), new Random(7L));

        assertTrue(found.isPresent(), "平地应能找到落点");
        final Location loc = found.orElseThrow();
        assertEquals(65.1, loc.getY(), 1e-6, "落点为地表上方 1 格，并按官方 +0.1 微抬");
        assertTrue(loc.getBlockX() < 0 || loc.getBlockX() > 15, "X 应在区域外（外扩 1 格）");
    }

    /** 准入判定拒绝时继续下一次尝试；全部拒绝则返回空——且尝试次数不超过 maxAttempts。 */
    @Test
    void rejectedCandidatesConsumeAttemptsUpToTheBound() {
        final AtomicInteger calls = new AtomicInteger();
        final Optional<Location> found = SafeLandingSearch.findOutside(
                region(),
                flat(64, Material.STONE),
                candidate -> {
                    calls.incrementAndGet();
                    return false;
                },
                new SafeLandingSearch.Options(15),
                new Random(7L));

        assertTrue(found.isEmpty(), "全部被拒时应返回空");
        assertEquals(15, calls.get(), "尝试次数应恰为 maxAttempts（官方 maxIt = 15）");
    }

    /** 地表高于区域顶部（人会被埋）→ 该点失败；全区域如此则返回空。 */
    @Test
    void buriedTerrainYieldsNoLanding() {
        // 地面在 y=300（高于区域顶部 200），区域内全是实体。
        final Optional<Location> found = SafeLandingSearch.findOutside(
                region(), flat(300, Material.STONE), null, SafeLandingSearch.Options.defaults(), new Random(7L));

        assertTrue(found.isEmpty(), "地表高于区域顶部时不应给出落点（否则会把玩家埋进方块）");
    }

    /** 一路到底都没有地面 → 返回空。 */
    @Test
    void bottomlessColumnYieldsNoLanding() {
        final SafeLandingSearch.BlockReader voidReader = new SafeLandingSearch.BlockReader() {
            @Override
            public boolean passable(final int x, final int y, final int z) {
                return true;
            }

            @Override
            public boolean isEmpty(final int x, final int y, final int z) {
                return true;
            }

            @Override
            public Material typeAt(final int x, final int y, final int z) {
                return Material.AIR;
            }
        };

        assertTrue(
                SafeLandingSearch.findOutside(
                                region(),
                                voidReader,
                                null,
                                SafeLandingSearch.Options.defaults(),
                                new Random(7L))
                        .isEmpty(),
                "全空列没有落点");
    }

    /** 官方 maxIt = 15 是默认值，须钉住。 */
    @Test
    void defaultAttemptsMatchOfficial() {
        assertEquals(15, SafeLandingSearch.Options.defaults().maxAttempts());
        assertEquals(15, SafeLandingSearch.DEFAULT_MAX_ATTEMPTS);
    }

    /** 区域边界必须有序（防误用）。 */
    @Test
    void regionRejectsUnorderedBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SafeLandingSearch.Region(this.world, 10, 0, 0, 5, 200, 15));
        assertThrows(IllegalArgumentException.class, () -> new SafeLandingSearch.Region(null, 0, 0, 0, 5, 200, 15));
    }

    // ------------------------------------------------------------------
    // 纵向就近搜索：官方 ResidencePlayerListener#getSafeLocation（RPL:1605-1628）
    // ------------------------------------------------------------------

    /** 高处落下：向下扫到地表，落在其上方一格（保留原 XZ 小数与朝向）。 */
    @Test
    void safeColumnAtFindsGroundBelow() {
        final SafeLandingSearch.BlockReader reader = flat(64, Material.STONE);
        final Location origin = new Location(this.world, 3.5, 100.0, 7.5);

        final Location loc = SafeLandingSearch.safeColumnAt(origin, reader, -64, 320).orElseThrow();

        assertEquals(65.0, loc.getY(), 1e-6, "地表 64 上方一格");
        assertEquals(3.5, loc.getX(), 1e-6, "X 保持不变");
        assertEquals(7.5, loc.getZ(), 1e-6, "Z 保持不变");
    }

    /** 已经贴着地面：同列向下立刻命中。 */
    @Test
    void safeColumnAtWorksWhenAlreadyNearGround() {
        final SafeLandingSearch.BlockReader reader = flat(64, Material.STONE);
        final Location origin = new Location(this.world, 3.0, 66.0, 3.0);

        assertEquals(
                65.0,
                SafeLandingSearch.safeColumnAt(origin, reader, -64, 320).orElseThrow().getY(),
                1e-6);
    }

    /** 落在方块内部（下扫时该格非空但其上方非空）→ 继续向下找，最终取该列地表。 */
    @Test
    void safeColumnAtRecoversFromBeingInsideTerrain() {
        final SafeLandingSearch.BlockReader reader = flat(64, Material.STONE);
        final Location origin = new Location(this.world, 3.0, 60.0, 3.0);

        assertEquals(
                65.0,
                SafeLandingSearch.safeColumnAt(origin, reader, -64, 320).orElseThrow().getY(),
                1e-6,
                "被埋时应向上取该列地表，而不是把人留在方块里");
    }

    /** 该列全空 → 返回空（由调用方决定回退到配置落点或出生点）。 */
    @Test
    void safeColumnAtReturnsEmptyForVoidColumn() {
        final SafeLandingSearch.BlockReader voidReader = new SafeLandingSearch.BlockReader() {
            @Override
            public boolean passable(final int x, final int y, final int z) {
                return true;
            }

            @Override
            public boolean isEmpty(final int x, final int y, final int z) {
                return true;
            }

            @Override
            public Material typeAt(final int x, final int y, final int z) {
                return Material.AIR;
            }
        };

        assertTrue(SafeLandingSearch.safeColumnAt(new Location(this.world, 3.0, 70.0, 3.0), voidReader, -64, 320)
                .isEmpty());
    }

    /** 尺寸按闭区间计算（官方 CuboidArea#getXSize）。 */
    @Test
    void regionSizesAreInclusive() {
        final SafeLandingSearch.Region r = region();
        assertEquals(16, r.sizeX());
        assertEquals(16, r.sizeZ());
        assertNotNull(r.world());
    }
}