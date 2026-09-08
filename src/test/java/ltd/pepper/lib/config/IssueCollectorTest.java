package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IssueCollectorTest {

    @Test
    void collectsAndCountsByLevel() {
        IssueCollector c = new IssueCollector();
        assertFalse(c.hasErrors());
        assertEquals(0, c.issues().size());
        c.add(new ConfigIssue(IssueLevel.WARN, "gate.mode", "未知值", "修正配置"));
        c.add(new ConfigIssue(IssueLevel.ERROR, "profiles.a", "父不存在", "禁用该 profile"));
        assertEquals(2, c.issues().size());
        assertTrue(c.hasErrors());
        assertEquals(1, c.count(IssueLevel.WARN));
        assertEquals(1, c.count(IssueLevel.ERROR));
    }

    @Test
    void issuesAreImmutableSnapshots() {
        IssueCollector c = new IssueCollector();
        c.add(new ConfigIssue(IssueLevel.WARN, "a", "b", "c"));
        List<ConfigIssue> snap1 = c.issues();
        c.add(new ConfigIssue(IssueLevel.ERROR, "x", "y", "z"));
        assertEquals(1, snap1.size(), "快照不随后续 add 变化");
        try {
            snap1.add(new ConfigIssue(IssueLevel.WARN, "", "", ""));
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("返回列表应不可变");
    }
}
