package ge.opensoft.openops;

import static ge.opensoft.openops.Core.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

@RestController
public class ApiController {
    private final Core core;

    public ApiController(Core core) {
        this.core = core;
    }

    @RequestMapping("/**")
    public ResponseEntity<?> dispatch(
            HttpServletRequest request, @RequestBody(required = false) byte[] bytes)
            throws Exception {
        return core.execute(
                request,
                bytes,
                this::route,
                e -> map("error", map("code", e.status, "message", e.getMessage())));
    }

    private Result route() throws Exception {
        core.parameters();
        String path = core.request().getRequestURI().replaceAll("/+$", ""),
                method = core.request().getMethod();
        if (path.equals("/api/v1/datasets")) {
            requireMethod(method, "GET", "POST");
            if (method.equals("GET"))
                return ok(
                        map(
                                "datasets",
                                core.rows("dataset").stream().map(this::metadata).toList()));
            core.writable();
            var item = Data.ingest(core.body());
            String id = UUID.randomUUID().toString();
            item.putAll(
                    map("id", id, "created_at", timestamp(), "row_count", Data.rows(item).size()));
            core.insert("dataset", id, item);
            return created(metadata(item));
        }
        Matcher dataset =
                Pattern.compile("/api/v1/datasets/([^/]+)(?:/(export\\.csv|query))?").matcher(path);
        if (dataset.matches()) {
            String id = dataset.group(1), action = dataset.group(2);
            if (action == null) {
                requireMethod(method, "GET", "DELETE");
                var item = core.get("dataset", id);
                if (method.equals("GET")) return ok(item);
                core.writable();
                core.delete("dataset", id);
                return empty();
            }
            if (action.equals("export.csv")) {
                requireMethod(method, "GET");
                var item = core.get("dataset", id);
                return new Result(
                        200,
                        Data.exportCsv(item).getBytes(StandardCharsets.UTF_8),
                        Map.of(
                                "Content-Type",
                                "text/csv; charset=utf-8",
                                "Content-Disposition",
                                "attachment; filename=\"dataset.csv\"",
                                "X-Dataset-SHA256",
                                (String) item.get("sha256")));
            }
            requireMethod(method, "POST");
            return ok(result(core.get("dataset", id), core.body()));
        }
        if (path.equals("/api/v1/conversations")) {
            requireMethod(method, "GET", "POST");
            if (method.equals("GET"))
                return ok(
                        map(
                                "conversations",
                                core.rows("conversation").stream().filter(this::mine).toList()));
            core.writable();
            var body = object(core.body(), "dataset_id", "title");
            core.get("dataset", text(body.get("dataset_id")));
            text(body.get("title"), 200, false);
            String id = UUID.randomUUID().toString();
            body.putAll(
                    map("id", id, "owner", core.actor().get("user"), "created_at", timestamp()));
            return created(core.insert("conversation", id, body));
        }
        Matcher messages = Pattern.compile("/api/v1/conversations/([^/]+)/messages").matcher(path);
        if (messages.matches()) {
            requireMethod(method, "GET", "POST");
            String id = messages.group(1);
            var conversation = owned("conversation", id);
            if (method.equals("GET"))
                return ok(
                        map(
                                "messages",
                                core.rows("message").stream()
                                        .filter(item -> item.get("conversation_id").equals(id))
                                        .toList()));
            core.writable();
            var body = object(core.body(), "question");
            var spec = Data.question(text(body.get("question"), 500, false));
            var item = result(core.get("dataset", conversation.get("dataset_id")), spec);
            String messageId = UUID.randomUUID().toString();
            item.putAll(
                    map("id", messageId, "conversation_id", id, "question", body.get("question")));
            return created(core.insert("message", messageId, item));
        }
        if (path.equals("/api/v1/saved-results")) {
            requireMethod(method, "GET", "POST");
            if (method.equals("GET"))
                return ok(
                        map(
                                "saved_results",
                                core.rows("saved").stream().filter(this::mine).toList()));
            core.writable();
            var body = object(core.body(), "name", "message_id");
            text(body.get("name"), 200, false);
            var message = core.get("message", text(body.get("message_id")));
            owned("conversation", (String) message.get("conversation_id"));
            String id = UUID.randomUUID().toString();
            return created(
                    core.insert(
                            "saved",
                            id,
                            map(
                                    "id",
                                    id,
                                    "name",
                                    body.get("name"),
                                    "owner",
                                    core.actor().get("user"),
                                    "snapshot",
                                    message)));
        }
        Matcher saved = Pattern.compile("/api/v1/saved-results/([^/]+)").matcher(path);
        if (saved.matches()) {
            requireMethod(method, "GET", "DELETE");
            String id = saved.group(1);
            var item = owned("saved", id);
            if (method.equals("GET")) return ok(item);
            core.writable();
            core.delete("saved", id);
            return empty();
        }
        throw new Failure(404, "Resource not found");
    }

    private Map<String, Object> result(Map<String, Object> dataset, Object spec) {
        return map(
                "dataset_id",
                dataset.get("id"),
                "dataset_sha256",
                dataset.get("sha256"),
                "query",
                spec,
                "result",
                Data.execute(dataset, spec),
                "created_at",
                timestamp());
    }

    private Map<String, Object> owned(String kind, String id) throws Exception {
        var item = core.get(kind, id);
        if (!mine(item)) throw new Failure(404, "Record not found");
        return item;
    }

    private boolean mine(Map<String, Object> item) {
        return item.get("owner").equals(core.actor().get("user"));
    }

    private Map<String, Object> metadata(Map<String, Object> item) {
        var value = new LinkedHashMap<>(item);
        value.remove("rows");
        return value;
    }

    private static void requireMethod(String actual, String... methods) {
        if (!List.of(methods).contains(actual)) throw new Failure(405, "Method not allowed");
    }
}
