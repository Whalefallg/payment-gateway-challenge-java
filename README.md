# Payment Gateway Challenge

一个基于 Java 17 和 Spring Boot 的简化支付网关。它校验商户请求，将有效请求转发给题目提供的银行模拟器，并以内存方式保存可查询的 Authorized 或 Declined 结果。

本项目仅用于技术评估，不适用于生产环境，也未经过 PCI DSS 评估或认证。

## 架构

```text
Merchant -> PaymentGatewayController -> PaymentGatewayService -> BankClient -> Bank simulator
                                      \-> PaymentsRepository (ConcurrentHashMap)
```

- Controller 负责 HTTP 契约和 Bean Validation。
- Service 负责流程编排、生成 UUID、构造脱敏记录。
- BankClient 独立负责银行线协议、超时和上游错误分类。
- Repository 仅保存不可变的脱敏 `PaymentRecord`，不保存完整 PAN 或 CVV。

## 环境要求

- JDK 17
- Docker 与 Docker Compose（仅真实模拟器冒烟测试需要）

## 构建、测试和启动

```bash
./gradlew clean build
docker compose up -d
./gradlew bootRun
```

Gateway 默认监听 `http://localhost:8090`，银行模拟器监听 `http://localhost:8080`。Swagger UI 位于：

```text
http://localhost:8090/swagger-ui/index.html
```

配置项均可由环境变量覆盖：

| 配置 | 环境变量 | 默认值 |
|---|---|---|
| 银行地址 | `BANK_BASE_URL` | `http://localhost:8080` |
| 连接超时 | `BANK_CONNECT_TIMEOUT` | `2s` |
| 读取超时 | `BANK_READ_TIMEOUT` | `2s` |

## API

### 创建支付

```http
POST /payments
Content-Type: application/json
```

```json
{
  "card_number": "2222405343240001",
  "expiry_month": 12,
  "expiry_year": 2028,
  "currency": "GBP",
  "amount": 1050,
  "cvv": "007"
}
```

Authorized 和 Declined 都表示已创建可查询记录，因此均返回 `201 Created`，并设置 `Location: /payments/{id}`。`201` 不表示银行已授权。

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "Authorized",
  "card_number_last_four": "0001",
  "expiry_month": 12,
  "expiry_year": 2028,
  "currency": "GBP",
  "amount": 1050
}
```

### 查询支付

```http
GET /payments/{id}
```

返回与创建时相同的业务字段；未知 UUID 返回 `404`，非法 UUID 语法返回 `400`。查询历史记录不会再次调用银行，也不会重新校验有效期。

## 校验规则

- `card_number`：字符串，14–19 位 ASCII 数字。
- `expiry_month`：JSON 整数，1–12。
- `expiry_year`：四位 JSON 整数；组合后的 `YearMonth` 必须严格晚于当前 UTC 月，当前月无效。
- `currency`：精确匹配 `USD`、`GBP` 或 `EUR`；不转换大小写、不裁剪空格。
- `amount`：32 位 JSON 整数。按题目保守解释允许零和负数；这不是生产支付产品的建议。
- `cvv`：字符串，3–4 位 ASCII 数字，保留前导零。

Jackson 禁止标量强制转换和浮点数转整数，因此字符串金额、浮点/科学计数法金额、数字 PAN/CVV、整数溢出、缺失字段和 `null` 均返回 `400`，且不会调用银行。

## 错误契约

错误响应包含稳定的 `code`、安全的 `message` 和服务端生成的 `request_id`。仅商户 POST 校验失败包含 `status: Rejected`。

| 情况 | HTTP | code |
|---|---:|---|
| 无效或格式错误的 POST | 400 | `INVALID_PAYMENT_REQUEST` |
| 非法 UUID | 400 | `INVALID_PAYMENT_ID` |
| 支付不存在 | 404 | `PAYMENT_NOT_FOUND` |
| 银行不可用/拒绝连接 | 503 | `BANK_UNAVAILABLE` |
| 银行连接或读取超时 | 504 | `BANK_TIMEOUT` |
| 银行响应无效或非预期 | 502 | `BANK_RESPONSE_INVALID` |
| 未预期应用故障 | 500 | `INTERNAL_ERROR` |

银行的 HTTP 503、超时、传输失败或歧义响应绝不会伪装成 Declined 或 Rejected，也不会创建记录。支付请求不会自动重试，因为超时后真实授权状态可能不确定。

## 银行模拟器与冒烟测试

模拟器按卡号末位返回确定结果：奇数 Authorized，非零偶数 Declined，零返回 503。Gateway 发出的字段为完整 PAN、`MM/yyyy`、currency、整数 amount 和字符串 CVV；敏感值只存在于处理当前调用所需的内存对象中。

启动模拟器和 Gateway 后运行：

```bash
./scripts/smoke-test.sh
```

脚本需要 `curl` 和 `python3`，验证 Authorized/GET、Declined/GET、银行 503 和无效商户请求。

## 可观测性与安全

- 每次请求生成新的关联 ID，通过 `X-Request-ID` 返回并在 MDC 中使用；请求结束后在 `finally` 中清理。
- 日志仅记录方法、路径、状态、耗时、银行结果或安全错误分类。
- 不记录原始请求体、完整 PAN、CVV、银行请求/响应体或不安全异常消息。
- 公共响应和内存记录仅保留卡号后四位；异常响应不回显拒绝值或解析器消息。
- Declined 是正常业务结果，不作为应用错误记录。

## 测试

```bash
./gradlew test
./gradlew clean build
```

普通自动化测试不依赖 Docker，包括原始 JSON MockMvc 校验、BankClient 线协议、受控本地 HTTP 读超时、Service 生命周期，以及真实 MVC→Jackson→Validation→Service→BankClient→HTTP stub→Repository 的完整应用上下文路径。

## 设计取舍与限制

- 使用 `ConcurrentHashMap` 保持题目规模简单；它不持久化、不支持崩溃恢复或多实例共享。
- 独立 BankClient 隔离敏感的上游协议和错误分类，无需额外接口或大型映射框架。
- 只有 Authorized/Declined 两种持久化业务状态，因此没有引入复杂状态机。
- 每次 POST 都是独立支付并生成新 UUID；没有幂等、重复支付保护或自动去重。
- 不自动重试银行请求；超时结果可能已在银行完成，需生产系统的查询或对账机制解决。
- 没有持久化存储、商户认证、资源所有权授权、端到端幂等、崩溃恢复或对账。
- 该实现未容器化 Gateway，也未增加监控基础设施，以保持 take-home 范围聚焦。
