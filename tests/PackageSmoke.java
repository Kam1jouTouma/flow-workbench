package course.workflow;

import java.nio.file.*;
import java.util.*;

public class PackageSmoke {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        int count = 0;
        try (var list = Files.list(data.resolve("flow-library"))) {
            for (Path p : list.sorted().toList()) {
                if (p.getFileName().toString().startsWith("09")) continue;
                var r =
                        BatchRunner.execute(
                                WorkflowApp.Flow.load(p),
                                data.resolve("smoke-runs"),
                                warnings -> System.out.println("WARN " + warnings));
                if (r.files().isEmpty()) throw new Exception("Empty output " + p);
                System.out.println("PASS " + p.getFileName() + " outputs=" + r.files().size());
                count++;
            }
        }
        if (count != 8) throw new Exception("Expected 8 examples");
        System.out.println("PACKAGE SMOKE PASS 8/8");
    }
}
