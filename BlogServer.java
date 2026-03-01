import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class BlogServer {
    private static final int PORT = 8080;
    private static final String DATA_DIR = "/opt/app/data";
    private static final String POSTS_FILE = DATA_DIR + "/posts.json";
    
    private static final Map<String, Map<String, Object>> posts = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> categories = new ConcurrentHashMap<>();
    
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(DATA_DIR));
        loadPosts();
        if (posts.isEmpty()) initSampleData();
        
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        // API: Get all posts
        server.createContext("/api/posts", ex -> {
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
            String category = params.get("category");
            String search = params.get("search");
            
            List<Map<String, Object>> result = posts.values().stream()
                .filter(p -> category == null || category.equals(p.get("category")))
                .filter(p -> search == null || 
                    ("" + p.get("title")).toLowerCase().contains(search.toLowerCase()) ||
                    ("" + p.get("content")).toLowerCase().contains(search.toLowerCase()))
                .sorted((a, b) -> ("" + b.get("createdAt")).compareTo("" + a.get("createdAt")))
                .collect(Collectors.toList());
            
            String json = "{\"posts\":" + toJsonArray(result) + ",\"total\":" + result.size() + ",\"categories\":" + toJsonArray(new ArrayList<>(categories.keySet())) + "}";
            sendJson(ex, json);
        });
        
        // API: Get single post
        server.createContext("/api/posts/", ex -> {
            String path = ex.getRequestURI().getPath();
            String id = path.substring("/api/posts/".length());
            Map<String, Object> post = posts.get(id);
            if (post != null) {
                sendJson(ex, toJson(post));
            } else {
                ex.sendResponseHeaders(404, 0);
            }
            ex.close();
        });
        
        // API: Create post
        server.createContext("/api/postsnew", ex -> {
            if (ex.getRequestMethod().equals("POST")) {
                try {
                    String body = new String(ex.getRequestBody().readAllBytes());
                    Map<String, Object> data = parseJson(body);
                    String id = System.currentTimeMillis() + "";
                    data.put("id", id);
                    data.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    posts.put(id, data);
                    categories.computeIfAbsent("" + data.get("category"), k -> new ArrayList<>()).add(id);
                    savePosts();
                    sendJson(ex, toJson(data));
                } catch (Exception e) { 
                    ex.sendResponseHeaders(400, 0); 
                }
                ex.close();
            }
        });
        
        // Static files
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                if (path.equals("/") || path.equals("")) path = "/index.html";
                if (path.equals("/post")) {
                    ex.getResponseHeaders().set("Location", "/post.html");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                    return;
                }
                
                byte[] content = Files.readAllBytes(Paths.get("/opt/app/static" + path));
                String ext = path.substring(path.lastIndexOf(".") + 1);
                String type = ext.equals("html") ? "text/html" : ext.equals("css") ? "text/css" : "application/javascript";
                ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
                ex.sendResponseHeaders(200, content.length);
                ex.getResponseBody().write(content);
            } catch (Exception e) {
                try { ex.sendResponseHeaders(404, 0); } catch (Exception ex2) {}
            }
            ex.close();
        });
        
        server.setExecutor(null);
        server.start();
        System.out.println("🎉 Blog Server running at http://localhost:" + PORT);
    }
    
    // Simple JSON utilities
    static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String p : query.split("&")) {
                String[] kv = p.split("=");
                if (kv.length == 2) try { params.put(kv[0], URLDecoder.decode(kv[1])); } catch (Exception e) {}
            }
        }
        return params;
    }
    
    static void sendJson(HttpExchange ex, String json) throws IOException {
        byte[] data = json.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }
    
    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof List) sb.append(toJsonArray((List) v));
            else if (v instanceof String) sb.append("\"").append(escapeJson("" + v)).append("\"");
            else if (v instanceof Number) sb.append(v);
            else sb.append("\"").append(escapeJson("" + v)).append("\"");
        }
        return sb.append("}").toString();
    }
    
    static String toJsonArray(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            Object v = list.get(i);
            if (v instanceof Map) sb.append(toJson((Map) v));
            else if (v instanceof String) sb.append("\"").append(escapeJson("" + v)).append("\"");
            else sb.append(v);
        }
        return sb.append("]").toString();
    }
    
    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    
    static Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{")) {
            json = json.substring(1, json.length() - 1);
            int depth = 0;
            int start = 0;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    parsePair(json.substring(start, i), map);
                    start = i + 1;
                }
            }
            parsePair(json.substring(start), map);
        }
        return map;
    }
    
    static void parsePair(String pair, Map<String, Object> map) {
        int colon = pair.indexOf(':');
        if (colon > 0) {
            String key = pair.substring(0, colon).trim();
            String value = pair.substring(colon + 1).trim();
            if (key.startsWith("\"")) key = key.substring(1, key.length() - 1);
            if (value.startsWith("[")) {
                List<Object> list = new ArrayList<>();
                if (value.length() > 2) {
                    value = value.substring(1, value.length() - 1);
                    for (String v : value.split(",")) {
                        v = v.trim();
                        if (v.startsWith("\"")) v = v.substring(1, v.length() - 1);
                        list.add(v);
                    }
                }
                map.put(key, list);
            } else if (value.startsWith("{")) {
                map.put(key, parseJson(value));
            } else if (value.startsWith("\"")) {
                map.put(key, value.substring(1, value.length() - 1));
            } else {
                try { map.put(key, Double.parseDouble(value)); } catch (Exception e) { map.put(key, value); }
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    static void loadPosts() {
        try {
            if (Files.exists(Paths.get(POSTS_FILE))) {
                String content = Files.readString(Paths.get(POSTS_FILE));
                if (content.trim().startsWith("[")) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    content = content.trim();
                    if (content.length() > 2) {
                        content = content.substring(1, content.length() - 1);
                        int depth = 0;
                        int start = 0;
                        for (int i = 0; i < content.length(); i++) {
                            char c = content.charAt(i);
                            if (c == '{' || c == '[') depth++;
                            else if (c == '}' || c == ']') depth--;
                            else if (c == ',' && depth == 0) {
                                list.add(parseJson(content.substring(start, i)));
                                start = i + 1;
                            }
                        }
                        list.add(parseJson(content.substring(start)));
                    }
                    for (Map<String, Object> post : list) {
                        String id = "" + post.get("id");
                        posts.put(id, post);
                        categories.computeIfAbsent("" + post.get("category"), k -> new ArrayList<>()).add(id);
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    static void savePosts() {
        try {
            List<Map<String, Object>> list = new ArrayList<>(posts.values());
            Files.writeString(Paths.get(POSTS_FILE), toJsonArray(list));
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    static void initSampleData() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String yesterday = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String twoDays = LocalDateTime.now().minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("id", "1");
        p1.put("title", "🎨 欢迎来到我的酷炫博客");
        p1.put("content", "这是一个完全使用 Java 构建的博客系统！\n\n## 特点\n\n- 🚀 无数据库，纯文件存储\n- 🎭 酷炫的前端设计\n- ⚡ 快速响应\n- 💾 数据持久化到 JSON 文件\n\n欢迎大家留言交流！");
        p1.put("category", "公告");
        p1.put("author", "OpenClaw");
        p1.put("tags", Arrays.asList("welcome", "blog", "java"));
        p1.put("coverImage", "https://picsum.photos/800/400?random=1");
        p1.put("createdAt", twoDays);
        
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("id", "2");
        p2.put("title", "💻 如何打造一个炫酷的个人博客");
        p2.put("content", "## 前言\n\n在这个看脸的时代，一个博客的外观非常重要！\n\n## 设计原则\n\n1. **简洁大方** - 不要过于复杂\n2. **色彩搭配** - 选择合适的配色\n3. **动效加成** - 适当的动画增加趣味性\n4. **响应式** - 适配各种设备\n\n## 技术栈\n\n- 原生 Java HTTP Server\n- 原生 HTML/CSS/JS\n- 纯前端渲染");
        p2.put("category", "技术");
        p2.put("author", "OpenClaw");
        p2.put("tags", Arrays.asList("前端", "设计", "博客"));
        p2.put("coverImage", "https://picsum.photos/800/400?random=2");
        p2.put("createdAt", yesterday);
        
        Map<String, Object> p3 = new LinkedHashMap<>();
        p3.put("id", "3");
        p3.put("title", "🌙 深夜编程的正确姿势");
        p3.put("content", "## 为什么夜间编程效率高？\n\n1. 安静无人打扰\n2. 思维更加清晰\n3. 灵感往往在深夜爆发\n\n## 推荐装备\n\n- ☕ 一杯热咖啡\n- 🎧 降噪耳机\n- 💡 护眼台灯\n- ⌨️ 机械键盘\n\nHappy Coding! 🚀");
        p3.put("category", "生活");
        p3.put("author", "OpenClaw");
        p3.put("tags", Arrays.asList("编程", "深夜", "效率"));
        p3.put("coverImage", "https://picsum.photos/800/400?random=3");
        p3.put("createdAt", now);
        
        for (Map<String, Object> p : List.of(p1, p2, p3)) {
            posts.put("" + p.get("id"), p);
            categories.computeIfAbsent("" + p.get("category"), k -> new ArrayList<>()).add("" + p.get("id"));
        }
        savePosts();
    }
}
