package com.demo.blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SpringBootApplication
@RestController
@CrossOrigin
public class BlogApplication {
    
    private static final String DATA_DIR = "/opt/app/data";
    private static final String POSTS_FILE = DATA_DIR + "/posts.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, BlogPost> posts = new ConcurrentHashMap<>();
    private Map<String, List<String>> categories = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        SpringApplication.run(BlogApplication.class, args);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            loadPosts();
            if (posts.isEmpty()) {
                initSampleData();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadPosts() {
        try {
            if (Files.exists(Paths.get(POSTS_FILE))) {
                ArrayNode array = (ArrayNode) mapper.readTree(Files.readString(Paths.get(POSTS_FILE)));
                for (var node : array) {
                    BlogPost post = mapper.convertValue(node, BlogPost.class);
                    posts.put(post.getId(), post);
                    categories.computeIfAbsent(post.getCategory(), k -> new ArrayList<>()).add(post.getId());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void savePosts() {
        try {
            ArrayNode array = mapper.createArrayNode();
            for (BlogPost post : posts.values()) {
                array.add(mapper.valueToTree(post));
            }
            Files.writeString(Paths.get(POSTS_FILE), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(array));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initSampleData() {
        List<BlogPost> samples = List.of(
            BlogPost.builder()
                .id("1")
                .title("🎨 欢迎来到我的酷炫博客")
                .content("这是一个完全使用 Spring Boot 构建的博客系统！\n\n## 特点\n\n- 🚀 无数据库，纯文件存储\n- 🎭 酷炫的前端设计\n- ⚡ 快速响应\n- 💾 数据持久化到 JSON 文件\n\n```java\nSystem.out.println(\"Hello World!\");\n```\n\n欢迎大家留言交流！")
                .category("公告")
                .author("OpenClaw")
                .tags(List.of("welcome", "blog", "spring-boot"))
                .coverImage("https://picsum.photos/800/400?random=1")
                .createdAt(LocalDateTime.now().minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build(),
            BlogPost.builder()
                .id("2")
                .title("💻 如何打造一个炫酷的个人博客")
                .content("## 前言\n\n在这个看脸的时代，一个博客的外观非常重要！\n\n## 设计原则\n\n1. **简洁大方** - 不要过于复杂\n2. **色彩搭配** - 选择合适的配色\n3. **动效加成** - 适当的动画增加趣味性\n4. **响应式** - 适配各种设备\n\n## 技术栈\n\n- Spring Boot 3\n- 原生 HTML/CSS/JS\n- 纯前端渲染")
                .category("技术")
                .author("OpenClaw")
                .tags(List.of("前端", "设计", "博客"))
                .coverImage("https://picsum.photos/800/400?random=2")
                .createdAt(LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build(),
            BlogPost.builder()
                .id("3")
                .title("🌙 深夜编程的正确姿势")
                .content("## 为什么夜间编程效率高？\n\n1. 安静无人打扰\n2. 思维更加清晰\n3. 灵感往往在深夜爆发\n\n## 推荐装备\n\n- ☕ 一杯热咖啡\n- 🎧 降噪耳机\n- 💡 护眼台灯\n- ⌨️ 机械键盘\n\n```javascript\n// 深夜代码片段\nconst night = true;\nconst focus = max;\nwhile(night) {\n    code();\n}\n```\n\nHappy Coding! 🚀")
                .category("生活")
                .author("OpenClaw")
                .tags(List.of("编程", "深夜", "效率"))
                .coverImage("https://picsum.photos/800/400?random=3")
                .createdAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build()
        );
        
        for (BlogPost post : samples) {
            posts.put(post.getId(), post);
            categories.computeIfAbsent(post.getCategory(), k -> new ArrayList<>()).add(post.getId());
        }
        savePosts();
    }

    // API Endpoints
    @GetMapping("/api/posts")
    public Map<String, Object> getAllPosts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {
        
        List<BlogPost> result = posts.values().stream()
            .filter(p -> category == null || p.getCategory().equals(category))
            .filter(p -> search == null || 
                p.getTitle().toLowerCase().contains(search.toLowerCase()) ||
                p.getContent().toLowerCase().contains(search.toLowerCase()))
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .collect(Collectors.toList());
        
        return Map.of(
            "posts", result,
            "total", result.size(),
            "categories", new ArrayList<>(categories.keySet())
        );
    }

    @GetMapping("/api/posts/{id}")
    public BlogPost getPost(@PathVariable String id) {
        return posts.get(id);
    }

    @PostMapping("/api/posts")
    public BlogPost createPost(@RequestBody BlogPost post) {
        String id = String.valueOf(System.currentTimeMillis());
        post.setId(id);
        post.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        posts.put(id, post);
        categories.computeIfAbsent(post.getCategory(), k -> new ArrayList<>()).add(id);
        savePosts();
        return post;
    }

    @DeleteMapping("/api/posts/{id}")
    public Map<String, String> deletePost(@PathVariable String id) {
        BlogPost post = posts.remove(id);
        if (post != null) {
            List<String> list = categories.get(post.getCategory());
            if (list != null) list.remove(id);
            savePosts();
            return Map.of("status", "deleted");
        }
        return Map.of("status", "not found");
    }

    @GetMapping("/")
    public ModelAndView index() {
        return new ModelAndView("forward:/static/index.html");
    }

    @GetMapping("/post/{id}")
    public ModelAndView postPage(@PathVariable String id) {
        return new ModelAndView("forward:/static/post.html");
    }

    @GetMapping("/write")
    public ModelAndView writePage() {
        return new ModelAndView("forward:/static/write.html");
    }
}
