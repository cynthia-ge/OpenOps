package ge.opensoft.openops;

import static ge.opensoft.openops.Core.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.*;

public final class Data {
    private Data() {}

    public static Map<String, Object> ingest(Object body) throws NoSuchAlgorithmException {
        var value = object(body, "name", "columns", "csv");
        text(value.get("name"), 120, false);
        if (!(value.get("columns") instanceof Map<?, ?> schema)
                || schema.isEmpty()
                || schema.size() > 50) throw new Failure(400, "Provide 1 to 50 typed columns");
        var columns = cast(schema);
        for (var column : columns.entrySet()) {
            if (!column.getKey().matches("[a-z][a-z0-9_]{0,63}")
                    || !Arrays.asList("string", "integer", "number", "boolean")
                            .contains(column.getValue()))
                throw new Failure(400, "Invalid column name or type");
        }
        var csv = parseCsv(text(value.get("csv"), 900000, false));
        if (csv.isEmpty() || !csv.getFirst().equals(new ArrayList<>(columns.keySet())))
            throw new Failure(400, "CSV header must match declared column order exactly");
        var rows = new ArrayList<Map<String, Object>>();
        for (var fields : csv.subList(1, csv.size())) {
            if (fields.size() != columns.size() || rows.size() >= 5000)
                throw new Failure(400, "Ragged CSV or more than 5000 rows");
            var row = new LinkedHashMap<String, Object>();
            int index = 0;
            for (var column : columns.entrySet()) {
                String field = fields.get(index++);
                Object parsed;
                try {
                    parsed =
                            switch ((String) column.getValue()) {
                                case "string" -> field;
                                case "integer" -> {
                                    if (!field.matches("-?[0-9]{1,15}"))
                                        throw new NumberFormatException();
                                    yield Long.parseLong(field);
                                }
                                case "number" -> {
                                    if (!field.strip()
                                            .matches(
                                                    "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                                        throw new NumberFormatException();
                                    double number = Double.parseDouble(field.strip());
                                    if (!Double.isFinite(number) || Math.abs(number) > 1e15)
                                        throw new NumberFormatException();
                                    yield number;
                                }
                                default -> {
                                    if (!List.of("true", "false").contains(field))
                                        throw new NumberFormatException();
                                    yield field.equals("true");
                                }
                            };
                } catch (NumberFormatException error) {
                    throw new Failure(400, "CSV contains an invalid typed value");
                }
                row.put(column.getKey(), parsed);
            }
            rows.add(row);
        }
        return map(
                "name",
                value.get("name"),
                "columns",
                columns,
                "rows",
                rows,
                "sha256",
                sha256(canonical(map("columns", columns, "rows", rows))));
    }

    static List<List<String>> parseCsv(String source) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false, closed = false, started = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else field.append(ch);
                continue;
            }
            if (ch == ',' || ch == '\r' || ch == '\n') {
                if (ch == ',' || started || !row.isEmpty()) row.add(field.toString());
                field.setLength(0);
                closed = false;
                started = false;
                if (ch != ',') {
                    rows.add(row);
                    row = new ArrayList<>();
                    if (ch == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                }
            } else {
                if (closed) throw new Failure(400, "Malformed CSV quoting");
                if (ch == '"' && !started) quoted = true;
                else field.append(ch);
                started = true;
            }
        }
        if (quoted) throw new Failure(400, "Unclosed CSV field");
        if (started || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    public static String exportCsv(Map<String, Object> dataset) {
        var columns = cast(dataset.get("columns"));
        var output = new StringBuilder(csvRow(new ArrayList<>(columns.keySet())));
        for (var row : rows(dataset))
            output.append(
                    csvRow(
                            columns.keySet().stream()
                                    .map(key -> String.valueOf(row.get(key)))
                                    .toList()));
        return output.toString();
    }

    private static String csvRow(List<String> fields) {
        return String.join(
                        ",",
                        fields.stream()
                                .map(value ->
                                        value.matches("(?s).*[\",\r\n].*")
                                                        || (fields.size() == 1 && value.isEmpty())
                                                ? "\"" + value.replace("\"", "\"\"") + "\""
                                                : value)
                                .toList())
                + "\r\n";
    }

    public static Map<String, Object> execute(Map<String, Object> dataset, Object body) {
        var spec = object(body, Set.of("select", "filter", "aggregate", "limit"), Set.of());
        var columns = cast(dataset.get("columns"));
        Object projection = spec.getOrDefault("select", new ArrayList<>(columns.keySet()));
        if (!(projection instanceof List<?> selected)
                || selected.isEmpty()
                || selected.stream()
                        .anyMatch(key -> !(key instanceof String) || !columns.containsKey(key))
                || new HashSet<>(selected).size() != selected.size())
            throw new Failure(400, "select must name unique known columns");
        Object bound = spec.getOrDefault("limit", 100);
        if (!(bound instanceof Integer limit) || limit < 1 || limit > 1000)
            throw new Failure(400, "limit must be an integer between 1 and 1000");
        var data = rows(dataset);
        if (spec.containsKey("filter")) {
            var filter = object(spec.get("filter"), "column", "op", "value");
            Object column = filter.get("column"),
                    op = filter.get("op"),
                    value = filter.get("value");
            if (!(column instanceof String key)
                    || !columns.containsKey(key)
                    || !Arrays.asList("eq", "gt", "lt", "contains").contains(op))
                throw new Failure(400, "Unsupported filter");
            String kind = (String) columns.get(key);
            boolean numeric = kind.equals("integer") || kind.equals("number");
            boolean valid =
                    kind.equals("string") && value instanceof String
                            || kind.equals("boolean") && value instanceof Boolean
                            || numeric
                                    && value instanceof Number n
                                    && n.doubleValue() >= -1e15
                                    && n.doubleValue() <= 1e15;
            if (!valid
                    || op.equals("contains") && !kind.equals("string")
                    || (op.equals("gt") || op.equals("lt")) && !numeric)
                throw new Failure(400, "Filter value and operator must match column type");
            data =
                    data.stream()
                            .filter(row -> {
                                Object current = row.get(key);
                                return switch ((String) op) {
                                    case "eq" ->
                                            numeric
                                                    ? ((Number) current).doubleValue()
                                                            == ((Number) value).doubleValue()
                                                    : current.equals(value);
                                    case "gt" ->
                                            ((Number) current).doubleValue()
                                                    > ((Number) value).doubleValue();
                                    case "lt" ->
                                            ((Number) current).doubleValue()
                                                    < ((Number) value).doubleValue();
                                    default -> ((String) current).contains((String) value);
                                };
                            })
                            .toList();
        }
        if (spec.containsKey("aggregate")) {
            if (spec.containsKey("select") || spec.containsKey("limit"))
                throw new Failure(400, "Aggregate cannot be combined with select or limit");
            var aggregate = object(spec.get("aggregate"), Set.of("op", "column"), Set.of("op"));
            Object op = aggregate.get("op"), column = aggregate.get("column"), value;
            if ("count".equals(op)) {
                if (column != null) throw new Failure(400, "count accepts no column");
                value = data.size();
            } else {
                if (!Arrays.asList("sum", "avg").contains(op)
                        || !(column instanceof String)
                        || !Arrays.asList("integer", "number").contains(columns.get(column)))
                    throw new Failure(400, "sum and avg require a numeric column");
                if (columns.get(column).equals("integer"))
                    value =
                            data.stream()
                                    .mapToLong(row -> ((Number) row.get(column)).longValue())
                                    .sum();
                else
                    value =
                            data.stream()
                                    .mapToDouble(row -> ((Number) row.get(column)).doubleValue())
                                    .sum();
                if (op.equals("avg"))
                    value = data.isEmpty() ? null : ((Number) value).doubleValue() / data.size();
            }
            return map("value", value, "matched_rows", data.size());
        }
        var projected = new ArrayList<Map<String, Object>>();
        for (var row : data.subList(0, Math.min(data.size(), limit))) {
            var result = new LinkedHashMap<String, Object>();
            for (Object key : selected) result.put((String) key, row.get(key));
            projected.add(result);
        }
        return map(
                "rows", projected, "matched_rows", data.size(), "truncated", data.size() > limit);
    }

    public static Map<String, Object> question(String question) {
        String value = text(question, 500, false).strip().toLowerCase(Locale.ROOT);
        if (value.equals("count rows")) return map("aggregate", map("op", "count"));
        if (value.equals("show rows")) return map();
        Matcher match = Pattern.compile("(sum|average) ([a-z][a-z0-9_]*)").matcher(value);
        if (!match.matches())
            throw new Failure(
                    400, "Supported questions: count rows, show rows, sum COLUMN, average COLUMN");
        return map(
                "aggregate",
                map("op", match.group(1).equals("sum") ? "sum" : "avg", "column", match.group(2)));
    }

    // Retain persisted dataset digest semantics: sorted keys, ASCII JSON and spaced separators.
    static String canonical(Object value) {
        if (value instanceof Map<?, ?> map)
            return "{"
                    + String.join(
                            ", ",
                            new TreeMap<>(cast(map))
                                    .entrySet().stream()
                                            .map(e ->
                                                    canonical(e.getKey())
                                                            + ": "
                                                            + canonical(e.getValue()))
                                            .toList())
                    + "}";
        if (value instanceof List<?> list)
            return "[" + String.join(", ", list.stream().map(Data::canonical).toList()) + "]";
        if (value instanceof Double number
                && number != 0
                && Math.abs(number) >= 1e-4
                && Math.abs(number) < 1e16) {
            String result = BigDecimal.valueOf(number).toPlainString();
            return result.contains(".") ? result : result + ".0";
        }
        if (value instanceof Double number) {
            String result = number.toString().toLowerCase(Locale.ROOT);
            if (result.contains("e")) {
                String[] parts = result.split("e");
                int exponent = Integer.parseInt(parts[1]);
                return parts[0].replaceAll("\\.0$", "")
                        + "e"
                        + (exponent < 0 ? "-" : "+")
                        + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
            }
            return result;
        }
        String json = JSON.writeValueAsString(value);
        var output = new StringBuilder();
        for (char ch : json.toCharArray()) {
            if (ch > 127) output.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else output.append(ch);
        }
        return output.toString();
    }

    static String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> dataset) {
        return (List<Map<String, Object>>) dataset.get("rows");
    }
}
