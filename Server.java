import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Employee Management REST API (JDBC + H2 by default). See README.md to run. */
public class Server {
    static class ApiException extends RuntimeException {
        final int status; final List<String> details;
        ApiException(int s, String m, List<String> d) { super(m); status = s; details = d; }
        ApiException(int s, String m) { this(s, m, List.of()); }
    }

    static Connection db;
    static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    static final Pattern PHONE = Pattern.compile("^[0-9+\\-() ]{7,20}$");
    static final Set<String> DEPTS = Set.of("Engineering", "HR", "Finance", "Marketing", "Sales", "Operations");
    static final Set<String> STATUSES = Set.of("ACTIVE", "ON_LEAVE", "TERMINATED");

    public static void main(String[] args) throws Exception {
        try {
            db = DriverManager.getConnection(System.getenv().getOrDefault("DB_URL", "jdbc:h2:./data/employees"),
                    System.getenv().getOrDefault("DB_USER", "sa"), System.getenv().getOrDefault("DB_PASS", ""));
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage() + "\nIs the H2 jar on the classpath? See README.md");
            return;
        }
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS employees (id INT AUTO_INCREMENT PRIMARY KEY, first_name VARCHAR(50) NOT NULL, "
                    + "last_name VARCHAR(50) NOT NULL, email VARCHAR(120) NOT NULL UNIQUE, phone VARCHAR(20), department VARCHAR(50) NOT NULL, "
                    + "job_title VARCHAR(80) NOT NULL, salary DECIMAL(12,2) NOT NULL, hire_date DATE NOT NULL, status VARCHAR(15) NOT NULL)");
        }
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer srv = HttpServer.create(new InetSocketAddress(port), 0);
        srv.createContext("/api/", Server::handle);
        srv.createContext("/", ex -> {
            Path f = Path.of("index.html");
            if (!ex.getRequestURI().getPath().equals("/") || !Files.exists(f)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] b = Files.readAllBytes(f);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        srv.start();
        System.out.println("Employee Management running at http://localhost:" + port);
    }

    // ---------- request handling & error mapping ----------
    static void handle(HttpExchange ex) throws IOException {
        try {
            if (ex.getRequestMethod().equals("OPTIONS")) { send(ex, 204, null); return; }
            route(ex);
        } catch (ApiException e) {
            send(ex, e.status, Map.of("error", Map.of("status", e.status, "message", e.getMessage(), "details", e.details)));
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23"))
                send(ex, 409, Map.of("error", Map.of("status", 409, "message", "An employee with this email already exists", "details", List.of())));
            else { e.printStackTrace(); send(ex, 500, Map.of("error", Map.of("status", 500, "message", "Database error"))); }
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", Map.of("status", 500, "message", "Internal server error")));
        }
    }

    static synchronized void route(HttpExchange ex) throws Exception {
        String m = ex.getRequestMethod();
        String[] p = ex.getRequestURI().getPath().replaceAll("^/api/?", "").split("/");
        if (!p[0].equals("employees") || p.length > 2) throw new ApiException(404, "Resource not found");

        if (p.length == 1) {
            if (m.equals("GET")) { list(ex); return; }
            if (m.equals("POST")) {
                Map<String, Object> v = validate(readBody(ex));
                try (PreparedStatement ps = db.prepareStatement("INSERT INTO employees(first_name,last_name,email,phone,department,job_title,salary,hire_date,status) VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, v); ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) {
                        k.next(); int id = k.getInt(1);
                        ex.getResponseHeaders().set("Location", "/api/employees/" + id);
                        send(ex, 201, find(id));
                    }
                }
                return;
            }
            throw new ApiException(405, "Method not allowed");
        }

        if (p[1].equals("stats")) { if (!m.equals("GET")) throw new ApiException(405, "Method not allowed"); stats(ex); return; }

        int id = parseId(p[1]);
        switch (m) {
            case "GET" -> send(ex, 200, find(id));
            case "PUT" -> {
                find(id);
                Map<String, Object> v = validate(readBody(ex));
                try (PreparedStatement ps = db.prepareStatement("UPDATE employees SET first_name=?,last_name=?,email=?,phone=?,department=?,job_title=?,salary=?,hire_date=?,status=? WHERE id=?")) {
                    bind(ps, v); ps.setInt(10, id); ps.executeUpdate();
                }
                send(ex, 200, find(id));
            }
            case "DELETE" -> {
                try (PreparedStatement ps = db.prepareStatement("DELETE FROM employees WHERE id=?")) {
                    ps.setInt(1, id);
                    if (ps.executeUpdate() == 0) throw new ApiException(404, "Employee " + id + " not found");
                }
                send(ex, 204, null);
            }
            default -> throw new ApiException(405, "Method not allowed");
        }
    }

    // ---------- database helpers ----------
    static void list(HttpExchange ex) throws Exception {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            String[] a = kv.split("=", 2);
            q.put(URLDecoder.decode(a[0], StandardCharsets.UTF_8), a.length > 1 ? URLDecoder.decode(a[1], StandardCharsets.UTF_8) : "");
        }
        String s = "%" + q.getOrDefault("search", "").trim().toLowerCase() + "%", d = q.getOrDefault("department", "").trim();
        String sql = "SELECT * FROM employees WHERE (LOWER(first_name) LIKE ? OR LOWER(last_name) LIKE ? OR LOWER(email) LIKE ?)"
                + (d.isEmpty() ? "" : " AND department=?") + " ORDER BY id";
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, s); ps.setString(2, s); ps.setString(3, s);
            if (!d.isEmpty()) ps.setString(4, d);
            try (ResultSet r = ps.executeQuery()) { while (r.next()) out.add(row(r)); }
        }
        send(ex, 200, out);
    }

    static void stats(HttpExchange ex) throws Exception {
        List<Object> by = new ArrayList<>(); int total = 0; double sum = 0;
        try (Statement st = db.createStatement();
             ResultSet r = st.executeQuery("SELECT department, COUNT(*) c, SUM(salary) t FROM employees GROUP BY department ORDER BY department")) {
            while (r.next()) { int c = r.getInt("c"); total += c; sum += r.getDouble("t"); by.add(Map.of("department", r.getString("department"), "count", c)); }
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("total", total); o.put("averageSalary", total == 0 ? 0 : Math.round(sum / total * 100) / 100.0); o.put("byDepartment", by);
        send(ex, 200, o);
    }

    static Map<String, Object> find(int id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM employees WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) return row(r); }
        }
        throw new ApiException(404, "Employee " + id + " not found");
    }

    static Map<String, Object> row(ResultSet r) throws SQLException {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", r.getInt("id")); o.put("firstName", r.getString("first_name")); o.put("lastName", r.getString("last_name"));
        o.put("email", r.getString("email")); o.put("phone", r.getString("phone")); o.put("department", r.getString("department"));
        o.put("jobTitle", r.getString("job_title")); o.put("salary", r.getBigDecimal("salary").doubleValue());
        o.put("hireDate", r.getDate("hire_date").toString()); o.put("status", r.getString("status"));
        return o;
    }

    static void bind(PreparedStatement ps, Map<String, Object> v) throws SQLException {
        ps.setString(1, (String) v.get("firstName")); ps.setString(2, (String) v.get("lastName")); ps.setString(3, (String) v.get("email"));
        ps.setString(4, (String) v.get("phone")); ps.setString(5, (String) v.get("department")); ps.setString(6, (String) v.get("jobTitle"));
        ps.setBigDecimal(7, BigDecimal.valueOf((Double) v.get("salary")));
        ps.setDate(8, java.sql.Date.valueOf((String) v.get("hireDate"))); ps.setString(9, (String) v.get("status"));
    }

    // ---------- validation ----------
    static int parseId(String s) {
        try { int i = Integer.parseInt(s); if (i > 0) return i; } catch (NumberFormatException ignored) { }
        throw new ApiException(400, "Invalid id '" + s + "': must be a positive integer");
    }

    static Map<String, Object> validate(Map<String, Object> in) {
        List<String> e = new ArrayList<>();
        String fn = str(in, "firstName", true, 2, 50, e), ln = str(in, "lastName", true, 1, 50, e), email = str(in, "email", true, 5, 120, e);
        String phone = str(in, "phone", false, 0, 20, e), dept = str(in, "department", true, 1, 50, e), title = str(in, "jobTitle", true, 2, 80, e);
        String hd = str(in, "hireDate", true, 1, 10, e), st = str(in, "status", false, 1, 15, e);
        if (email != null && !EMAIL.matcher(email).matches()) e.add("email is not a valid email address");
        if (phone != null && !phone.isEmpty() && !PHONE.matcher(phone).matches()) e.add("phone must be 7-20 digits/symbols (+ - ( ) space)");
        if (dept != null && !DEPTS.contains(dept)) e.add("department must be one of " + new TreeSet<>(DEPTS));
        if (st == null) st = "ACTIVE"; else if (!STATUSES.contains(st)) e.add("status must be ACTIVE, ON_LEAVE or TERMINATED");
        if (hd != null) try { LocalDate.parse(hd); } catch (Exception x) { e.add("hireDate must be a valid date (yyyy-MM-dd)"); }
        double sal = 0; Object sv = in.get("salary");
        if (sv == null) e.add("salary is required");
        else if (!(sv instanceof Double d)) e.add("salary must be a number");
        else if (d < 0 || d > 10_000_000) e.add("salary must be between 0 and 10000000");
        else sal = d;
        if (!e.isEmpty()) throw new ApiException(422, "Validation failed", e);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("firstName", fn); o.put("lastName", ln); o.put("email", email.toLowerCase()); o.put("phone", phone == null || phone.isEmpty() ? null : phone);
        o.put("department", dept); o.put("jobTitle", title); o.put("salary", sal); o.put("hireDate", hd); o.put("status", st);
        return o;
    }

    static String str(Map<String, Object> in, String k, boolean req, int min, int max, List<String> e) {
        Object v = in.get(k);
        if (v == null) { if (req) e.add(k + " is required"); return null; }
        if (!(v instanceof String s)) { e.add(k + " must be a string"); return null; }
        s = s.trim();
        if (s.length() < min || s.length() > max) { e.add(k + " must be " + min + "-" + max + " characters"); return null; }
        return s;
    }

    // ---------- minimal JSON (flat objects in, anything out) ----------
    static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().contains("application/json")) throw new ApiException(415, "Content-Type must be application/json");
        String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (b.isEmpty()) throw new ApiException(400, "Request body is required");
        if (!b.startsWith("{") || !b.endsWith("}")) throw new ApiException(400, "Malformed JSON: body must be a JSON object");
        Map<String, Object> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?\\d+(?:\\.\\d+)?|true|false|null)").matcher(b);
        while (m.find()) {
            String t = m.group(2); Object v;
            if (m.group(3) != null) v = m.group(3).replace("\\\"", "\"").replace("\\\\", "\\");
            else if (t.equals("null")) v = null;
            else if (t.equals("true") || t.equals("false")) v = Boolean.valueOf(t);
            else v = Double.valueOf(t);
            out.put(m.group(1), v);
        }
        if (out.isEmpty() && b.length() > 2) throw new ApiException(400, "Malformed JSON");
        return out;
    }

    static String json(Object o) {
        if (o == null) return "null";
        if (o instanceof Map<?, ?> m) { StringJoiner j = new StringJoiner(",", "{", "}"); m.forEach((k, v) -> j.add(json(k.toString()) + ":" + json(v))); return j.toString(); }
        if (o instanceof Collection<?> c) { StringJoiner j = new StringJoiner(",", "[", "]"); c.forEach(v -> j.add(json(v))); return j.toString(); }
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        return "\"" + o.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    static void send(HttpExchange ex, int code, Object body) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        if (body == null) { ex.sendResponseHeaders(code, -1); ex.close(); return; }
        byte[] b = json(body).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
