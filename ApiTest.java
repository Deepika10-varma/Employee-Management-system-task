import java.net.URI;
import java.net.http.*;
import java.util.regex.*;

/** Integration tests. Start the server first, then: java ApiTest */
public class ApiTest {
    static final HttpClient c = HttpClient.newHttpClient();
    static final String BASE = "http://localhost:8080/api";
    static int pass, fail;

    static HttpResponse<String> call(String method, String path, String body, String ctype) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(BASE + path)).header("Content-Type", ctype);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> call(String m, String p, String body) throws Exception { return call(m, p, body, "application/json"); }

    static void expect(String name, int status, HttpResponse<String> r) {
        boolean ok = r.statusCode() == status;
        if (ok) pass++; else fail++;
        System.out.printf("%s %-36s expected %d got %d%n", ok ? "PASS" : "FAIL", name, status, r.statusCode());
    }
    static String id(HttpResponse<String> r) { Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(r.body()); return m.find() ? m.group(1) : "0"; }

    static String emp(String email, String extra) {
        return "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"email\":\"" + email + "\",\"department\":\"Engineering\","
                + "\"jobTitle\":\"Developer\",\"salary\":55000,\"hireDate\":\"2024-03-15\"" + extra + "}";
    }

    public static void main(String[] a) throws Exception {
        String email = "t" + System.currentTimeMillis() + "@example.com";

        var e = call("POST", "/employees", emp(email, ""));            expect("create employee", 201, e);
        String id = id(e);
        expect("duplicate email", 409, call("POST", "/employees", emp(email, "")));
        expect("invalid email", 422, call("POST", "/employees", emp("bad", "")));
        expect("missing fields", 422, call("POST", "/employees", "{}"));
        expect("negative salary", 422, call("POST", "/employees", emp("n" + email, ",\"salary\":-5").replace("\"salary\":55000,", "")));
        expect("invalid department", 422, call("POST", "/employees", emp("d" + email, "").replace("Engineering", "Pirates")));
        expect("invalid date", 422, call("POST", "/employees", emp("x" + email, "").replace("2024-03-15", "15/03/2024")));
        expect("malformed JSON", 400, call("POST", "/employees", "{oops"));
        expect("wrong content type", 415, call("POST", "/employees", emp(email, ""), "text/plain"));
        expect("get employee", 200, call("GET", "/employees/" + id, null));
        expect("get missing employee", 404, call("GET", "/employees/99999", null));
        expect("non-numeric id", 400, call("GET", "/employees/abc", null));
        expect("update employee", 200, call("PUT", "/employees/" + id, emp(email, "").replace("55000", "65000")));
        expect("update missing employee", 404, call("PUT", "/employees/99999", emp(email, "")));
        expect("list + search", 200, call("GET", "/employees?search=asha&department=Engineering", null));
        expect("stats", 200, call("GET", "/employees/stats", null));
        expect("method not allowed", 405, call("PATCH", "/employees", "{}"));
        expect("unknown resource", 404, call("GET", "/widgets", null));
        expect("delete employee", 204, call("DELETE", "/employees/" + id, null));
        expect("delete again", 404, call("DELETE", "/employees/" + id, null));

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
