# 黑马点评（hm-dianping）

> 一个基于 Spring 框架开发的点评系统，支持用户注册、登录、发布点评、查看商家信息等功能。

## 项目简介

主要功能包括：

- 用户注册、登录及权限管理
- 商家信息浏览与搜索
- 点评发布、修改与删除
- 点评内容分页展示
- 管理员后台管理

## 技术栈

- Java 21
- Spring Boot
- MyBatis-Plus
- MySQL
- Redis
- Maven
## 结构
src/
├── main/
│   ├── java/com/hm/dianping/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── mapper/
│   │   ├── entity/
│   │   └── HmDianpingApplication.java
│   └── resources/
│       ├── application.yml
│       └── mapper/
