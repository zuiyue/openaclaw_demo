package com.demo.blog;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlogPost {
    private String id;
    private String title;
    private String content;
    private String category;
    private String author;
    private List<String> tags;
    private String coverImage;
    private String createdAt;
    private int views;
}
