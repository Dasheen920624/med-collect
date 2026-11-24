# 医疗文献采集系统

Java 21、Spring Boot 和 Vue 3 应用。支持指定网址、栏目、Europe PMC 文献检索、可配置的全网搜索、4.23 业务文献选采及手工上传。资料入库后保留原始来源和原件，审核员可以按 4.23 知识模板手工建稿或调用 OpenAI 兼容模型解析。同步前核对重复知识和字段差异，人工审核通过后按处理决定新增、更新原业务记录或登记跳过。

[医疗文献采集功能与验收记录](docs/collection-audit.md) · [原型分析、业务存储和开发计划](docs/implementation.md) · [分节点交接和验收](docs/handoff.md) · [服务器部署与更新](docs/deployment.md)

## 构建与本地运行

需要 Java 21、Maven 3.9、Node.js 22.12+ 和 pnpm 11。以下命令使用 PowerShell 7。如果主机并存多个 Node/JDK，检查当前终端的 `node -v`、`mvn -v` 和 `JAVA_HOME`。

~~~powershell
pnpm --dir web install --frozen-lockfile
pnpm --dir web build
mvn -f backend/pom.xml clean verify
& "$env:JAVA_HOME/bin/java.exe" -jar backend/target/med-collect-0.1.10.jar
~~~

打开 [本地采集工作台](http://127.0.0.1:8085/)。`local` 使用当前运行目录下 `.medcollect/db` 的 H2，原件保存到 `.medcollect/originals`。回环地址的本地体验账户为 `admin`，密码为 `medcollect-local`。运行 Vue 开发服务器可用 `pnpm --dir web dev`，其 `/api` 请求代理到 `127.0.0.1:8085`。更改代理地址时设置 `MEDCOLLECT_DEV_API`。

## Oracle 与内网运行

`oracle` 配置通过环境变量连接部署方的 Oracle 服务。JDBC 地址格式为 `jdbc:oracle:thin:@//oracle.example.org:1521/ORCL`。Liquibase 以配置的采集账户在其默认表空间创建 `MC_` 表和迁移记录。连接同一 Oracle schema 的实例须使用同一份原件存储。联调时为测试 schema 配置对应的文件目录。

~~~powershell
$env:MEDCOLLECT_DB_URL = Read-Host 'Oracle JDBC 地址'
$env:MEDCOLLECT_DB_USER = Read-Host 'Oracle 采集账户'
$env:MEDCOLLECT_DB_PASSWORD = Read-Host 'Oracle 采集账户密码' -MaskInput
$env:MEDCOLLECT_ORIGINAL_DIR = Read-Host '此 Oracle schema 对应的原件目录'
$env:MEDCOLLECT_ADMIN_PASSWORD = Read-Host '采集系统管理员密码' -MaskInput
$env:MEDCOLLECT_CDSS_URL = Read-Host '业务系统 HTTPS 地址'
$env:MEDCOLLECT_CDSS_USER = Read-Host '业务账户'
$env:MEDCOLLECT_CDSS_PASSWORD = Read-Host '业务账户密码' -MaskInput
$env:MEDCOLLECT_MODEL_URL = Read-Host 'OpenAI 兼容网关地址'
$env:MEDCOLLECT_MODEL_KEY = Read-Host '模型 API 密钥' -MaskInput
$env:MEDCOLLECT_CDSS_CERT_SHA256 = Read-Host '业务系统叶证书 SHA-256 指纹，可留空使用信任链'
& "$env:JAVA_HOME/bin/java.exe" -jar backend/target/med-collect-0.1.10.jar --spring.profiles.active=oracle
~~~

业务 HTTPS 使用 JVM 信任链，也支持用现场核验的指纹固定叶证书。在系统设置测试业务连接并刷新模板后，可设置 `MEDCOLLECT_CDSS_SYNC_ENABLED=true` 并重启采集服务。

| 变量 | 用途 |
| --- | --- |
| `MEDCOLLECT_DB_URL`, `MEDCOLLECT_DB_USER`, `MEDCOLLECT_DB_PASSWORD` | Oracle JDBC 地址、采集 schema 账户及密码，启用 Oracle 配置时必填 |
| `MEDCOLLECT_ORIGINAL_DIR` | 原件存储根目录。Docker 容器使用 `/var/lib/med-collect/originals`，挂载到服务器 `/zoesoft/med-collect/state/originals` |
| `MEDCOLLECT_ADMIN_USER`, `MEDCOLLECT_ADMIN_PASSWORD` | 首次初始化时导入管理员账号 |
| `MEDCOLLECT_REVIEWER_USER`, `MEDCOLLECT_REVIEWER_PASSWORD` | 首次初始化时导入可选的审核员账号，配对设置 |
| `MEDCOLLECT_BIND`, `MEDCOLLECT_PORT`, `MEDCOLLECT_HTTPS` | 监听地址、端口和 HTTPS 会话 Cookie；默认回环地址和 8085，HTTPS 反代时将 Cookie 开关设为 true |
| `MEDCOLLECT_CDSS_URL`, `MEDCOLLECT_CDSS_USER`, `MEDCOLLECT_CDSS_PASSWORD` | 业务地址及服务端登录账户 |
| `MEDCOLLECT_CDSS_CERT_SHA256`, `MEDCOLLECT_CDSS_SYNC_ENABLED` | 指纹和同步开关 |
| `MEDCOLLECT_MODEL_URL`, `MEDCOLLECT_MODEL_NAME`, `MEDCOLLECT_MODEL_KEY` | 模型初始地址、模型和环境密钥。地址默认 `http://127.0.0.1:13000/v1`，模型默认 `deepseek-flash` |
| `MEDCOLLECT_SETTINGS_KEY` | 32 字节密钥的 Base64；设置后可在界面保存 API 密钥，数据库内使用 AES-GCM 密文。部署时用同一个值解密 |
| `MEDCOLLECT_SEARX_URL` | 配置 SearXNG 的服务根地址或 `/search`。其 `search.formats` 需包含 `json` |
| `MEDCOLLECT_PRIVATE_SOURCE_HOSTS` | 逗号分隔的、允许采集的内网主机名或 IP。内网 SearXNG 也在这里登记 |
| `MEDCOLLECT_MANUAL_SOURCE_HOSTS` | 检验、检查说明书允许的发布机构主机名，以逗号分隔。部署时填入已核实的中国大陆机构域名。留空时关闭这项来源筛选 |
| `MEDCOLLECT_SCHEDULING_ENABLED` | 是否自动运行已启用的采集计划，默认为 true |

## 注册与用户管理

在登录页点「注册账号」创建审核员账号，注册完成即可登录。管理员登录后在「用户管理」新建账号、调整角色、启用或停用账号、重置密码。账号和 BCrypt 密码哈希保存在数据库。初始化配置在首启时导入现有账号，之后以数据库中的账号设置为准。

## 从采集文献写入 4.23

文献来自出版社网址、文献检索、全网检索或上传。核验原件后，用目标业务模板解析为知识稿，核对摘要、结构化字段和字典。首页的「同步至 4.23」显示已审核待同步、异常及相同已跳过的稿件。点击「查看写入记录」可以区分实际新增和按原 ID 更新，打开业务知识，或实时核验类别、标题、摘要与 PDF 原件。

在「审核与同步」先查重并核对差异。自动匹配使用文献 DOI 或同类标题。整理为疾病、检验等知识时以业务知识名称识别目标，审核人也可以按业务名称选择已有记录。审核通过后办理同步。4.23 的专科、病种、模板和字典用于构建稿件。需要参考业务库已有文献时，在「采集任务 → 从 4.23 导入文献」选采。[写入、回读与验收](docs/deployment.md#017-写入-423-与回读)

## 操作流程

1. 从**采集任务**选择指定网址下载、手工上传、全网搜索或业务文献库选采。网址支持直接输入或使用已配置来源，并可选择栏目采集。任务可关联多个来源，选择手动、按间隔、单次定时或每日定时。启用全网检索候选选采时，从搜索执行详情选择结果并启动采集。智能规划生成可编辑的采集建议。
2. 在**文献原文库**选择左侧学科病种目录，按分类、格式或获取方式查询。可从列表预览、下载原件，核验摘要和采集记录，并给一份或多份原文分配分类。各类型原件保存为服务器文件。PDF 模型分析时从原件临时提取文字，其他类型的提取文本随原件保存。对扫描件或锁定 PDF，可由审核人核验原件并补录摘要。Europe PMC 记录没有可取得的开放获取 PDF 时，保留源记录、摘要和链接。
3. 在**文献解析**查看每份原文的解析状态、已生成知识数和最近生成时间。核验原文后，可按一个或多个 4.23 模板手工建稿或调用模型。核对摘要、模板字段和所选业务字典项目，再提交人工审核。管理员可在**解析模板**维护新稿件使用的提取说明和必填规则。
4. 在**审核与同步**打开稿件，左侧查看原件，右侧核对类别、摘要和字段。点击「检查重复与差异」，多个候选时选择对应的业务知识。无匹配可在人工审核后新增，内容一致可登记跳过。有差异时，带有本采集系统新增回执的业务 ID 可在审核后更新并留存版本。4.23 原有知识进入「业务原有 · 仅对照」，可查看原文和字段差异。获批新增或更新的 PDF 以摘要写入正文，原件作为附件绑定。
5. 采集系统首建的业务知识可「发起新版本」并重新提交审核。在「版本记录」按业务 ID 查看历次同步的差异和更新前后快照。同步中断时从详情重试，系统先回读业务记录及附件。查重后业务记录有变化时，按最新差异重新审核。[同步操作与异常处理](docs/deployment.md#知识同步查重与版本记录)

采集支持 PDF、HTML、DOCX、TXT、CSV、XLS、XLSX，单份原件上限 20 MB，每批可上传 1 至 20 份。一个采集任务可配置最多 100 个来源或网址，获取 1 至 100 条结果。更长的网址清单可用分批工具办理和续跑。Oracle 保存相对文件路径、来源与摘要、执行记录、知识修订状态及同步回执。部署备份须同时包含 Oracle 和原件目录。[大批量网址采集](docs/deployment.md#大批量网址采集) · [交接记录](docs/handoff.md)
