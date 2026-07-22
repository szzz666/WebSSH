# WebSSH

一个基于 Java 的浏览器 SSH 服务器工作台。通过网页连接远程 Linux 主机，并在桌面式界面中使用终端、SFTP 文件管理、文本编辑、资源监控和 GNU Screen 后台会话。

## 功能特性

- 支持密码和 PEM/OpenSSH 私钥认证
- 连接前展示并确认 SSH 主机指纹
- 基于 xterm.js 的交互式 SSH 终端
- 创建、恢复和终止 GNU Screen 后台会话
- 通过 SFTP 浏览、上传、下载、复制、移动、重命名和删除文件
- 在线编辑不超过 2 MiB 的 UTF-8 文本文件
- 修改远程文件权限，创建桌面快捷方式
- ZIP 文件压缩、解压与进度显示
- 查看 CPU、内存、Swap、磁盘、网络和系统负载等指标
- 保存连接配置、操作记录和界面偏好
- 浅色、深色及跟随系统主题
- 桌面端和移动端响应式界面

## 技术栈

- 后端：Java 21、Spark Java、Java-WebSocket、JSch
- 前端：Vue 3、Axios、xterm.js、Ace Editor
- 构建：Maven、Maven Shade Plugin
- 数据传输：REST API、WebSocket、SSH、SFTP

## 环境要求

### WebSSH 服务端

- JDK 21 或更高版本
- Maven 3.8 或更高版本
- 可访问目标 SSH 主机的网络环境
- 可用端口 `8080` 和 `8081`，或在配置中设置相应端口

### 远程主机

- Linux 系统及可用的 SSH 服务
- 使用后台终端和运行文件功能时需要安装 GNU Screen
- 资源监控依赖常见 Linux 工具和接口，如 `/proc`、`awk`、`free`、`df`、`ps` 和 `uname`

Debian/Ubuntu 可使用以下命令安装 Screen：

```bash
sudo apt update
sudo apt install screen
```

RHEL/CentOS/Fedora 可使用：

```bash
sudo dnf install screen
```

## 快速开始

### 1. 构建项目

```bash
mvn clean package
```

构建完成后，可执行的完整依赖 JAR 位于：

```text
target/WebSSH-1.0-SNAPSHOT.jar
```

### 2. 启动服务

```bash
java -jar target/WebSSH-1.0-SNAPSHOT.jar
```

首次启动会在当前工作目录自动生成 `config.yml`。默认服务地址为：

```text
http://127.0.0.1:8080
```

### 3. 建立 SSH 连接

1. 在浏览器打开 WebSSH。
2. 点击“新建连接”。
3. 填写目标主机、SSH 端口、用户名和认证信息。
4. 点击“测试连接”验证认证配置。
5. 建立连接时核对并确认主机指纹。
6. 从桌面或程序坞打开终端、文件管理或资源监控。

## 配置说明

`config.yml` 在程序启动时自动创建，修改配置后需要重启服务。默认配置如下：

```yaml
log:
  # 日志等级: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL
  level: INFO
settings:
  # 核心线程数
  cordPoolSize: 1
  # 最大线程数，-1 表示 CPU 核心数 * 4
  maxPoolSize: -1
  # 非核心线程存活时间（秒）
  keepAliveTime: 60
  # 最大任务队列长度
  maxQueueSize: 100
server:
  # Web 服务监听地址
  host: 127.0.0.1
  # 静态页面和 REST API 端口
  port: 8080
  # 终端与任务流 WebSocket 端口
  wsPort: 8081
ssh:
  # SSH 建连与认证超时（毫秒）
  connectTimeoutMs: 15000
  # 监控与任务命令超时（毫秒）
  commandTimeoutMs: 30000
  # 单文件上传大小上限，默认 100 MiB
  maxUploadBytes: 104857600
```

> [!IMPORTANT]
> 当前浏览器端的 WebSocket 地址固定使用端口 `8081`。如需修改 `server.wsPort`，还需要同步修改 `src/main/resources/public/webssh.js` 中 `wsUrl` 方法使用的端口并重新构建。

若需要让局域网中的其他设备访问，可将 `server.host` 设置为 `0.0.0.0`，并在防火墙中放行 HTTP 和 WebSocket 端口。对外开放前请先阅读下方的安全说明。

## 使用说明

### 终端

主终端通过 WebSocket 转发 SSH Shell 的输入和输出，支持窗口尺寸同步、自动重连、复制、清屏和字号调整。

### 文件管理

文件管理器通过 SFTP 操作远程文件。目录下载时会由服务端实时打包为 ZIP；压缩和解压任务在后台执行。在线编辑器仅支持 UTF-8 文本文件，单个文件最大为 2 MiB。

WebSSH 桌面快捷方式保存在远程主机的：

```text
/root/.webssh/desktop
```

因此桌面快捷方式功能目前主要面向以 `root` 用户连接的主机。普通用户可能因权限不足无法使用该功能。

### Screen 会话

后台会话由远程主机上的 GNU Screen 提供。关闭或最小化 Screen 窗口不会终止后台程序，可稍后从程序坞恢复。会话元数据保存在远程用户的：

```text
$HOME/.webssh/screen-sessions
```

### 资源监控

资源监控通过 SSH 在远程主机执行只读系统命令并汇总结果。部分精简发行版、非 Linux 系统或受限 Shell 可能无法提供完整指标。

## 安全说明

> [!WARNING]
> 本项目当前没有 WebSSH 页面登录、用户鉴权和访问控制。不要将服务直接暴露到公网。

- 默认仅监听 `127.0.0.1`，建议保持此设置，或通过带身份认证和 TLS 的反向代理访问。
- SSH 密码和私钥可选择保存在浏览器 `localStorage`，保存时为明文。请勿在不可信或多人共用设备上保存凭据。
- SSH 会话保存在服务端进程内存中，进程退出后会断开。
- 请只确认通过可信渠道核对过的主机指纹。
- 远程文件、命令执行、重启服务器等操作使用所连接 SSH 用户的权限，使用高权限账号时应格外谨慎。
- 项目本身未配置 HTTPS。生产环境应使用反向代理提供 HTTPS/WSS，并限制可信来源和网络范围。
- 页面中的 Vue、Axios、Ace Editor 及默认壁纸依赖外部 CDN 或网络服务；受限网络环境下部分组件可能无法加载。

## 项目结构

```text
WebSSH/
├─ pom.xml
└─ src/main/
   ├─ java/top/szzz666/
   │  ├─ Main.java                  # 程序入口
   │  ├─ config/                    # YAML 配置加载
   │  ├─ server/                    # HTTP、WebSocket、任务与 Screen 管理
   │  ├─ ssh/                       # SSH 连接与认证
   │  └─ tools/                     # 通用工具
   └─ resources/
      ├─ log4j2.xml                 # 日志配置
      └─ public/                    # Web 前端静态资源
```

## 开发与验证

编译项目：

```bash
mvn compile
```

执行完整构建：

```bash
mvn clean package
```

当前项目尚未包含自动化测试。修改 SSH、SFTP 或 WebSocket 相关代码后，建议至少手动验证以下流程：

- 密码认证和私钥认证
- 主机指纹确认
- 终端输入、输出、尺寸变化与重连
- 文件上传、下载、编辑、复制、移动、压缩和解压
- Screen 会话创建、恢复和终止
- 资源监控与远程服务器重启
- 桌面和移动端页面布局

## 常见问题

### 页面可以打开，但终端无法连接

检查 `8081` 端口是否被占用或被防火墙拦截，并确认浏览器能够连接该端口。如果通过反向代理部署，还需要正确代理 WebSocket 流量。

### Screen 会话创建失败

确认远程主机已安装 `screen`，且当前 SSH 用户能够执行 `screen` 并写入 `$HOME/.webssh`。

### 文本编辑器无法加载

Ace Editor 在首次使用时从 jsDelivr 加载。请检查浏览器网络连接、内容安全策略及 CDN 可访问性。

### 局域网设备无法访问

确认 `server.host` 已设置为 `0.0.0.0` 或具体网卡地址，同时放行 `server.port` 与 `server.wsPort`。修改监听范围会扩大攻击面，请配合防火墙或可信反向代理使用。

## License

仓库当前未声明开源许可证。未经版权所有者明确许可，不应假定可以复制、修改或分发本项目。
