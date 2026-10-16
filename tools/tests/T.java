import java.util.ArrayList;

/** 零依赖断言工具。任何一条失败都会让进程以退出码 1 结束。 */
public final class T {
    private static int passed = 0;
    private static final ArrayList<String> failures = new ArrayList<String>();

    public static void eq(String name, Object actual, Object expected) {
        boolean ok = (actual == null) ? (expected == null) : actual.equals(expected);
        if (ok) {
            passed++;
        } else {
            failures.add(name + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void ok(String name, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failures.add(name + ": condition was false");
        }
    }

    public static void done() {
        System.out.println("passed=" + passed + " failed=" + failures.size());
        for (String f : failures) System.out.println("  FAIL " + f);
        if (!failures.isEmpty()) System.exit(1);
    }
}
