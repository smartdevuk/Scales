package uk.co.violetscale.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises the same CSV reader and spreadsheet-text handling used by file import and export. */
public final class CsvFixtures {
    private static int checks;

    public static void main(String[] args) {
        List<String> special = Arrays.asList("weight", "waist, cm", "quote \"", "two\r\nlines", "");
        List<String> second = Arrays.asList("63.5", "", "", "", "");
        StringBuilder csv = new StringBuilder("\uFEFF");
        DataCsv.row(csv, special);
        DataCsv.row(csv, second);
        List<List<String>> rows = new ArrayList<>();
        DataCsv.read(csv.toString(), (fields, number) -> {
            check(number == rows.size() + 1);
            rows.add(fields);
        });
        check(rows.size() == 2);
        check(special.equals(rows.get(0)));
        check(second.equals(rows.get(1)));
        for (String raw : Arrays.asList("=1+1", "   @SUM(A1)", "-100", "\tvalue", "'was quoted")) {
            String protectedValue = DataCsv.protectText(raw);
            check(protectedValue.startsWith("'"));
            check(raw.equals(DataCsv.unprotectText(protectedValue)));
        }
        check("before breakfast".equals(DataCsv.protectText("before breakfast")));
        for (String malformed : Arrays.asList("a,\"unterminated", "x,un\"quoted", "\"done\"garbage")) {
            boolean rejected = false;
            try { DataCsv.read(malformed, (fields, n) -> { }); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected);
        }
        System.out.println(checks + " CSV fixture checks passed");
    }

    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("CSV check " + checks);
    }
}
