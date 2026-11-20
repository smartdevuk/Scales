package uk.co.violetscale.probe;

import java.util.ArrayList;
import java.util.List;

/** RFC 4180-style quoting, including doubled quotes and multiline notes. */
final class DataCsv {
    static final int MAX_ROWS = 50000;
    interface RowReader { void accept(List<String> cells, int recordNumber); }

    static void read(String text, RowReader reader) {
        int i = text.startsWith("\uFEFF") ? 1 : 0;
        int row = 0;
        while (i < text.length()) {
            if (++row > MAX_ROWS + 1) throw new IllegalArgumentException("The file exceeds 50,000 records.");
            List<String> fields = new ArrayList<>();
            boolean nextField;
            do {
                if (fields.size() >= 80) throw invalid(row);
                StringBuilder field = new StringBuilder();
                boolean quoted = i < text.length() && text.charAt(i) == '"';
                if (quoted) i++;
                boolean closed = !quoted;
                while (i < text.length()) {
                    char ch = text.charAt(i);
                    if (ch == '"') {
                        if (!quoted) throw invalid(row);
                        i++;
                        if (i < text.length() && text.charAt(i) == '"') i++;
                        else { closed = true; break; }
                    } else {
                        if (!quoted && (ch == ',' || ch == '\n' || ch == '\r')) break;
                        i++;
                    }
                    field.append(ch);
                    if (field.length() > 65536) throw invalid(row);
                }
                if (!closed) throw invalid(row);
                fields.add(field.toString());
                nextField = false;
                if (i < text.length()) {
                    char sep = text.charAt(i++);
                    if (sep == ',') nextField = true;
                    else if (sep == '\r') { if (i < text.length() && text.charAt(i) == '\n') i++; }
                    else if (sep != '\n') throw invalid(row);
                }
            } while (nextField);
            reader.accept(fields,row);
        }
    }

    private static IllegalArgumentException invalid(int row) {
        return new IllegalArgumentException("Invalid CSV at record " + row + ".");
    }
    static void row(StringBuilder out, List<String> cells) {
        boolean first = true;
        for (String cell : cells) {
            if (!first) out.append(',');
            first = false;
            String s = cell == null ? "" : cell;
            if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
                out.append('"').append(s.replace("\"","\"\"")).append('"');
            } else out.append(s);
        }
        out.append("\r\n");
    }

    // A leading apostrophe also escapes an actual apostrophe in exported free text.
    static String protectText(String value) {
        if (value.isEmpty()) return value;
        char first = value.charAt(0);
        String trimmed = value.trim();
        if (first == '\'' || first == '\t' || first == '\r' || first == '\n'
                || (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)) return "'" + value;
        return value;
    }
    static String unprotectText(String value) {
        if (!value.startsWith("'")) return value;
        String rest = value.substring(1);
        return protectText(rest).equals(value) ? rest : value;
    }
}
