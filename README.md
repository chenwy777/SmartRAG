# 企业级RAG知识库系统（SmartRAG）

SmartRAG 是一个企业级的 RAG 知识库系统，采用检索增强生成（RAG）技术，提供智能文档处理和检索能力。

核心技术栈包括 Elasticsearch、Kafka、WebSocket、Spring Security、Docker、MySQL 和 Redis。

它的目标是帮助企业和个人更高效地管理和利用知识库中的信息，支持多租户架构，允许用户通过自然语言查询知识库，并获得基于自身文档的 AI 生成响应。

系统允许用户：

- 上传和管理各种类型的文档
- 自动处理和索引文档内容
- 使用自然语言查询知识库
- 接收基于自身文档的 AI 生成响应

后端技术栈：

+ 框架 : Spring Boot 3.4.2 (Java 17)
+ 数据库 : MySQL 8.0
+ ORM : Spring Data JPA
+ 缓存 : Redis
+ 搜索引擎 : Elasticsearch 8.10.0
+ 消息队列 : Apache Kafka
+ 文件存储 : MinIO
+ 文档解析 : Apache Tika
+ 安全认证 : Spring Security + JWT
+ AI集成 : DeepSeek API/本地 Ollama+豆包 Embedding
+ 实时通信 : WebSocket
+ 依赖管理 : Maven
+ 响应式编程 : WebFlux

后端的整体项目结构：

```bash
src/main/java/com/smartrag/
├── SmartRAGApplication.java      # 主应用程序入口
├── client/                       # 外部API客户端
├── config/                       # 配置类
├── consumer/                     # Kafka消费者
├── controller/                   # REST API端点
├── entity/                       # 数据实体
├── exception/                    # 自定义异常
├── handler/                      # WebSocket处理器
├── model/                        # 领域模型
├── repository/                   # 数据访问层
├── service/                      # 业务逻辑
└── utils/                        # 工具类
```

前端技术栈：

+ 框架 : Vue 3 + TypeScript
+ 构建工具 : Vite
+ UI组件 : Naive UI
+ 状态管理 : Pinia
+ 路由 : Vue Router
+ 样式 : UnoCSS + SCSS
+ 图标 : Iconify
+ 包管理 : pnpm

前端的整体项目结构：

```bash
frontend/
├── packages/           # 可重用模块
├── public/             # 静态资源
├── src/                # 主应用程序代码
│   ├── assets/         # SVG图标，图片
│   ├── components/     # Vue组件
│   ├── layouts/        # 页面布局
│   ├── router/         # 路由配置
│   ├── service/        # API集成
│   ├── store/          # 状态管理
│   ├── views/          # 页面组件
│   └── ...            # 其他工具和配置
└── ...               # 构建配置文件
```

## 核心功能

系统围绕文档管理、检索增强生成、多租户权限和实时通信提供以下核心能力。

### 知识库管理

SmartRAG提供了完整的文档上传与解析功能，支持文件分片上传和断点续传，并支持标签进行组织管理。文档可以是公开的，也可以是私有的，并且可以与特定的组织标签关联，以便更好地进行权限分类。

### AI驱动的RAG实现

SmartRAG的核心是 RAG 实现：

- 将上传的文档进行语义分块
- 调用豆包 Embedding 模型为每个文本块生成高维向量
- 将向量存储到 Elasticsearch 以支持语义搜索和关键词搜索
- 可以根据用户的查询检索相关文档
- 为 LLM 提供完整的上下文，从而生成更准确、基于文档的响应内容

### 企业级多租户

SmartRAG通过组织标签支持多租户架构。每个用户可以创建或加入一个或多个组织，每个组织可以拥有独立的知识库和文档管理。这样，企业可以在同一系统中管理多个团队或部门的知识库，而无需担心数据混淆或权限问题。

### 实时通信

系统采用 WebSocket 技术，提供用户与 AI 系统之间的实时交互，支持响应式聊天界面，便于知识检索和 AI 互动。

## 前置环境

在开始之前，请确保已安装以下软件：

- Java 17
- Maven 3.8.6 或更高版本
- Node.js 18.20.0 或更高版本
- pnpm 8.7.0 或更高版本
- MySQL 8.0
- Elasticsearch 8.10.0
- MinIO 8.5.12
- Kafka 3.2.1
- Redis 7.0.11
- Docker（可选，用于运行 Redis、MinIO、Elasticsearch 和 Kafka 等服务）

## 架构设计

SmartRAG的架构具备一个现代化的、云原生应用程序的特点，具有清晰的关注点分离、可扩展的组件和与 AI 技术的集成。模块化设计允许随着技术的发展，特别是快速变化的 AI 集成领域，未来可以扩展和替换单个组件。

控制层用于处理 HTTP 请求，验证输入，管理请求/响应格式化，并将业务逻辑委托给服务层。控制器按领域功能组织。遵循 RESTful 设计原则，集成了性能监控和日志记录，用于跟踪 API 使用和故障排除。

```java
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    @Autowired
    private DocumentService documentService;
    
    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        // 参数验证和委托给服务
        documentService.deleteDocument(fileMd5);
        // 响应处理
    }
}
```

服务层主要用来处理应用的业务逻辑，具有事务感知能力，能够处理跨越多个数据源的操作。

```java
@Service
public class DocumentService {
    @Autowired
    private FileUploadRepository fileUploadRepository;
    
    @Autowired
    private MinioClient minioClient;
    
    @Autowired
    private ElasticsearchService elasticsearchService;
    
    @Transactional
    public void deleteDocument(String fileMd5) {
        // 文档删除的业务逻辑
        // 协调多个仓储和系统
    }
}
```

数据访问层使用 Spring Data JPA 进行数据库操作，提供了对 MySQL 的 CRUD 操作。

```java
@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findByFileMd5(String fileMd5);
    
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
}
```

实体层由映射到数据库表的 JPA 实体以及用于 API 请求和响应的 DTO（数据传输对象）组成。

```java
@Entity
public class FileUpload {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String fileMd5;
    private String fileName;
    private String userId;
    private boolean isPublic;
    private String orgTag;
    // 其他字段和方法
}
```

## 环境配置与启动

本地开发按“准备 `.env` → 启动基础服务 → 启动后端 → 启动前端”的顺序进行。

### 1. 准备项目根目录 `.env`

项目根目录的 `.env` 用于保存后端本地运行和部署相关配置。首次使用时先复制模板：

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env
```

后端启动时会通过 `DotenvEnvironmentPostProcessor` 自动读取项目根目录 `.env`，所以无论是 IDE 直接运行 `SmartRAGApplication`，还是在项目根目录执行 `mvn spring-boot:run`，都会优先使用这里的配置。

`.env` 里当前主要有三类配置：

- 应用运行配置：MySQL、Redis、Kafka、MinIO、Elasticsearch、JWT、AI Provider 等
- 初始化与安全配置：如 `ADMIN_BOOTSTRAP_*`、`APP_AUTH_REGISTRATION_MODE`、`SECURITY_ALLOWED_ORIGINS`
- 前端部署配置：如 `DEPLOY_SERVER_HOST`、`DEPLOY_SERVER_USER`、`DEPLOY_SERVER_KEY`、`DEPLOY_TARGET_DIR`、`DEPLOY_HEALTHCHECK_URL`

几个关键项建议优先确认：

- `SPRING_PROFILES_ACTIVE=dev`：本地源码启动默认使用 `dev`
- `SPRING_DATASOURCE_*`、`SPRING_DATA_REDIS_*`：数据库和 Redis 连接
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`、`MINIO_*`、`ELASTICSEARCH_*`：基础依赖地址
- `JWT_SECRET_KEY`：必须是 Base64 字符串，可用 `openssl rand -base64 32` 生成
- `ADMIN_BOOTSTRAP_ENABLED`：仅首次创建管理员时临时改为 `true`，创建完成后改回 `false`

说明：

- `.env` 是后端和部署脚本共用的根配置
- 前端自己的 Vite 变量仍放在 `frontend/.env`、`frontend/.env.test`、`frontend/.env.prod`
- `pnpm run dev` 实际使用的是 `vite --mode test`，默认会读取 `frontend/.env.test`

### 2. 启动本地基础服务

`infra.sh` 是现在推荐的本地基础设施启动入口，用来统一管理 `minio`、`kafka`、`elasticsearch`。

使用前请通过 `MINIO_DIR`、`KAFKA_DIR`、`ELASTICSEARCH_DIR` 环境变量指定本机安装目录；默认目录为项目下的 `services/`。该脚本适用于 Bash 环境，也可通过 `docs/docker-compose.yaml` 启动基础服务。

```bash
# 启动全部基础服务
./infra.sh start

# 启动指定服务
./infra.sh start minio kafka

# 查看状态
./infra.sh status

# 查看某个服务日志
./infra.sh logs elasticsearch

# 输出本地访问地址
./infra.sh urls
```

如果只想启动部分依赖，也可以按服务名传参：

```bash
./infra.sh start minio kafka
```

### 3. 启动后端

基础服务就绪后，在项目根目录启动 Spring Boot：

```bash
mvn spring-boot:run
```

也可以直接在 IDE 中运行 `src/main/java/com/smartrag/SmartRAGApplication.java`，效果一样，都会自动读取根目录 `.env`。

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm run dev
```

前端开发页面默认访问 `http://localhost:9527`；后端 API 地址为 `http://localhost:8081/api/v1`，对应配置在 `frontend/.env.test`。

### 5. 服务器脚本启动

如果是服务器上用 jar 包方式运行，可以参考根目录的 `launch.sh.example`。建议先复制成你自己的启动脚本，再按需调整 JDK、Maven 和 jar 名称。脚本支持先加载指定 `.env`，再执行 `start`、`restart`、`stop`、`status`、`logs` 等命令。

```bash
cp launch.sh.example launch.sh
chmod +x launch.sh

# 使用默认 .env 启动
./launch.sh start

# 使用指定环境文件启动
./launch.sh start -e .env.prod
```

其中：

- `start`：会先 `git pull`，再重新打包并启动
- `restart`：直接重启现有 jar
- `status` / `logs`：查看进程状态和日志

### 6. 前端部署脚本

`deploy-front.sh` 用于构建前端、打 zip 包、上传到服务器，并在远端替换 `/home/www/SmartRAG-Front/dist`。脚本会自动读取根目录 `.env` 中的部署配置。

```bash
# 直接构建并部署前端
./deploy-front.sh
```

部署脚本默认会执行这些步骤：

- 进入 `frontend` 执行 `pnpm build`
- 打包 `dist` 为 zip 文件并上传到服务器
- 删除远端旧的 `dist` 目录并解压新包
- 检查远端 `dist/index.html` 是否存在
- 请求 `DEPLOY_HEALTHCHECK_URL` 做健康检查

如果只想复用已有的前端构建产物，可以在执行时跳过构建：

```bash
DEPLOY_SKIP_BUILD=1 ./deploy-front.sh
```
