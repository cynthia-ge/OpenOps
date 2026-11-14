package ge.opensoft.openops;

import static ge.opensoft.openops.Core.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class DataTest {
    Map<String, Object> body() {
        return map(
                "name",
                "Readings",
                "columns",
                map("site", "string", "units", "integer", "ratio", "number", "active", "boolean"),
                "csv",
                "site,units,ratio,active\nWest,3,1.5,true\nEast,-2,2,false\nWest,5,0,true\n");
    }

    @Test
    void decimalGrammarAndSignedZeroEquality() throws Exception {
        var dataset =
                Data.ingest(
                        map(
                                "name",
                                "Zero",
                                "columns",
                                map("value", "number"),
                                "csv",
                                "value\n-0.0\n+0e10\n"));
        assertEquals(
                2,
                Data.execute(
                                dataset,
                                map("filter", map("column", "value", "op", "eq", "value", 0.0)))
                        .get("matched_rows"));
        assertThrows(
                Failure.class,
                () ->
                        Data.ingest(
                                map(
                                        "name",
                                        "Hex",
                                        "columns",
                                        map("value", "number"),
                                        "csv",
                                        "value\n0x1.0p2\n")));
    }

    @Test
    void typedIngestionAndQueries() throws Exception {
        var dataset = Data.ingest(body());
        assertEquals(
                map("site", "West", "units", 3L, "ratio", 1.5, "active", true),
                Data.rows(dataset).getFirst());
        assertEquals(dataset.get("sha256"), Data.ingest(body()).get("sha256"));
        assertEquals(
                map("rows", List.of(map("units", 3L)), "matched_rows", 3, "truncated", true),
                Data.execute(dataset, map("select", List.of("units"), "limit", 1)));
        assertEquals(false, Data.execute(dataset, map()).get("truncated"));
        for (var filter :
                List.of(
                        map("column", "site", "op", "eq", "value", "West", "expected", 2),
                        map("column", "site", "op", "contains", "value", "as", "expected", 1),
                        map("column", "units", "op", "eq", "value", 3, "expected", 1),
                        map("column", "units", "op", "gt", "value", 3, "expected", 1),
                        map("column", "units", "op", "lt", "value", 3, "expected", 1),
                        map("column", "active", "op", "eq", "value", true, "expected", 2))) {
            Object expected = filter.remove("expected");
            assertEquals(
                    expected, Data.execute(dataset, map("filter", filter)).get("matched_rows"));
        }
        assertEquals(3, Data.execute(dataset, map("aggregate", map("op", "count"))).get("value"));
        assertEquals(
                6L,
                Data.execute(dataset, map("aggregate", map("op", "sum", "column", "units")))
                        .get("value"));
        assertEquals(
                3.5 / 3,
                Data.execute(dataset, map("aggregate", map("op", "avg", "column", "ratio")))
                        .get("value"));
        var empty = body();
        empty.put("csv", "site,units,ratio,active\n");
        var emptyDataset = Data.ingest(empty);
        assertNull(
                Data.execute(emptyDataset, map("aggregate", map("op", "avg", "column", "units")))
                        .get("value"));
        assertEquals(
                0L,
                Data.execute(emptyDataset, map("aggregate", map("op", "sum", "column", "units")))
                        .get("value"));
        assertEquals(map("aggregate", map("op", "count")), Data.question(" COUNT ROWS "));
        assertEquals(map(), Data.question("show rows"));
        assertEquals(
                map("aggregate", map("op", "sum", "column", "units")), Data.question("sum units"));
        assertEquals(
                map("aggregate", map("op", "avg", "column", "ratio")),
                Data.question("average ratio"));
        assertThrows(Failure.class, () -> Data.question("delete everything"));
        String exported = Data.exportCsv(dataset);
        var roundtrip = body();
        roundtrip.put("csv", exported);
        assertEquals(dataset.get("sha256"), Data.ingest(roundtrip).get("sha256"));
    }

    @Test
    void rejectMalformedIngestion() {
        var tooMany = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 51; i++) tooMany.put("a" + i, "string");
        for (Object schema :
                List.of(List.of(), Map.of(), map("Bad", "string"), map("a", List.of()), tooMany)) {
            var body = body();
            body.put("columns", schema);
            assertThrows(Failure.class, () -> Data.ingest(body));
        }
        for (String csv :
                List.of(
                        "x,y\n1,2",
                        "site,units,ratio,active\nx,1",
                        "site,units,ratio,active\n" + "x,1,2,true\n".repeat(5001),
                        "site,units,ratio,active\nx,x,2,true",
                        "site,units,ratio,active\nx,1,nan,true",
                        "site,units,ratio,active\nx,1,1e20,true",
                        "site,units,ratio,active\nx,1,2,yes",
                        "site,units,ratio,active\n\"broken",
                        "site,units,ratio,active\n\"bad\"x,1,2,true")) {
            var body = body();
            body.put("csv", csv);
            assertThrows(
                    Failure.class,
                    () -> Data.ingest(body),
                    csv.substring(0, Math.min(40, csv.length())));
        }
    }

    @Test
    void rejectInvalidQueryShapesAndOperators() throws Exception {
        var dataset = Data.ingest(body());
        for (Object selected :
                List.of(
                        List.of(),
                        List.of("site", "site"),
                        Map.of(),
                        List.of(List.of()),
                        List.of("missing")))
            assertThrows(Failure.class, () -> Data.execute(dataset, map("select", selected)));
        for (Object limit : List.of(true, 0, 1001))
            assertThrows(Failure.class, () -> Data.execute(dataset, map("limit", limit)));
        for (var filter :
                List.of(
                        map("column", List.of(), "op", "eq", "value", 1),
                        map("column", "units", "op", "no", "value", 1),
                        map("column", "units", "op", "eq", "value", true),
                        map("column", "units", "op", "eq", "value", Double.NaN),
                        map("column", "units", "op", "contains", "value", 1),
                        map("column", "site", "op", "gt", "value", "a")))
            assertThrows(Failure.class, () -> Data.execute(dataset, map("filter", filter)));
        for (var spec :
                List.of(
                        map("aggregate", map("op", "count"), "select", List.of("site")),
                        map("aggregate", map("op", "count"), "limit", 2),
                        map("aggregate", map("op", "count", "column", "site")),
                        map("aggregate", map("op", "mean", "column", "units")),
                        map("aggregate", map("op", "sum", "column", List.of())),
                        map("aggregate", map("op", "sum", "column", "site"))))
            assertThrows(Failure.class, () -> Data.execute(dataset, spec));
    }

    @Test
    void csvAndDigestBoundaries() throws Exception {
        assertEquals(List.of(), Data.parseCsv(""));
        assertEquals(
                List.of(List.of("a", "b"), List.of(), List.of("x", "")),
                Data.parseCsv("a,b\r\n\nx,"));
        var body =
                map(
                        "name",
                        "Single column",
                        "columns",
                        map("text", "string"),
                        "csv",
                        "text\n\"\"\n\"λ, \"\"quoted\"\"\nnext\"\n");
        var dataset = Data.ingest(body);
        String exported = Data.exportCsv(dataset);
        assertTrue(exported.contains("\"\"\r\n"));
        assertTrue(exported.contains("\"λ, \"\"quoted\"\"\nnext\""));
        body.put("csv", exported);
        assertEquals(dataset.get("sha256"), Data.ingest(body).get("sha256"));
        assertEquals(
                "{\"a\": 1000000000000000.0, \"b\": [1e-05, -0.0, \"\\u03bb\\ud83c\\udf0d\"]}",
                Data.canonical(map("b", List.of(0.00001, -0.0, "λ🌍"), "a", 1e15)));
        assertEquals("1e+20", Data.canonical(1e20));
    }
}
