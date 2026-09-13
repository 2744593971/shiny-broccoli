package com.duli.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data // 使用 Lombok 自动生成 get/set
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class VlogBO {
    private String id;
    private String vlogerId;
    private String url;
    private String cover;
    private String title;
    private Integer width;
    private Integer height;
    private Integer likeCounts;
    private Integer commentsCounts;
}