# BL-002 — Hợp đồng REST, lỗi và correlation

Issue [#3](https://github.com/ThanhDat19/app-bank/issues/3). Áp dụng cho Spring MVC REST endpoints của core.
BL-002 không thêm API nghiệp vụ hoặc thay đổi dữ liệu. Các endpoint chỉ dùng cho test không được đóng gói
trong JAR; API nghiệp vụ đầu tiên sẽ được xây sau IAM ở BL-003.

## Success và request ID

Success giữ nguyên DTO của endpoint, không bọc thêm envelope. Response có `X-Correlation-ID`.
Client có thể gửi đúng một header này, dài 1–64 ký tự ASCII theo
`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. Thiếu, rỗng, quá dài, ký tự khác hoặc nhiều header thì server tạo UUID.
Header không hợp lệ không làm request thất bại và không được phản chiếu.

ID được gắn vào request attribute và MDC `correlationId` trong thời gian xử lý dispatch; filter dọn MDC
trong finally hoặc khôi phục context trước đó. ASYNC/ERROR dispatch dùng lại ID trong request attribute.
Worker threads/tác vụ background không tự nhận MDC; phải thiết kế context propagation khi bổ sung async/SSE.

Không đặt số tài khoản, token hoặc PII vào ID. ID client cung cấp chưa được xác thực, có thể trùng;
không dùng làm khóa authorization, idempotency key hoặc định danh giao dịch tài chính.

## Error response

Content-Type `application/problem+json`, `Cache-Control: no-store`. HTTP status và `status` trong JSON
phải khớp nhau. Header và `correlationId` trong body phải khớp nhau.

```json
{
  "type": "urn:banklab:problem:validation-failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "urn:uuid:4b0c89cf-711a-4c1d-8909-a2d819604778",
  "code": "VALIDATION_FAILED",
  "correlationId": "client-session-002",
  "errors": [{"field": "displayName", "code": "NotBlank"}]
}
```

`type` là URN ổn định theo code. `instance` là occurrence UUID mới, không dùng URL request vì URL có thể
chứa thông tin cá nhân/token. `errors` luôn là array, rỗng nếu không có lỗi theo field; không chứa rejectedValue,
custom validation message, SQL, stack trace hoặc exception message. Client dựa vào `code`, không parse `detail`.
Thông điệp công khai hiện dùng tiếng Anh; UI tự ánh xạ code sang tiếng Việt.

| HTTP | Code | Trigger |
| --- | --- | --- |
| 400 | INVALID_REQUEST | JSON hỏng, thiếu tham số, sai kiểu tham số |
| 400 | VALIDATION_FAILED | Request DTO không qua Bean Validation |
| 401 | UNAUTHENTICATED | BL-003: thiếu/sai bearer access token; thêm WWW-Authenticate: Bearer |
| 403 | ACCESS_DENIED | BL-003: đã xác thực nhưng thiếu quyền |
| 404 | RESOURCE_NOT_FOUND | Không có route/resource |
| 405 | METHOD_NOT_ALLOWED | Sai HTTP method; giữ header Allow |
| 406 | NOT_ACCEPTABLE | Không hỗ trợ response media type yêu cầu |
| 415 | UNSUPPORTED_MEDIA_TYPE | Không hỗ trợ request Content-Type |
| 4xx khác | REQUEST_REJECTED | Lỗi giao thức khác do MVC xác định, giữ HTTP status |
| 500/5xx | INTERNAL_ERROR | Lỗi nội bộ; response chỉ thông điệp chung |

## Business exception contract

Application module có thể ném `BusinessException(code, kind)` với code hằng số thuộc module,
ví dụ `ACCOUNT_FROZEN`, không lấy code/message trực tiếp từ dữ liệu khách hàng.

| Kind | HTTP | Ví dụ use case tương lai |
| --- | --- | --- |
| NOT_FOUND | 404 | Không tìm thấy resource trong phạm vi được phép xem |
| CONFLICT | 409 | Trạng thái/idempotency payload xung đột |
| RULE_VIOLATION | 422 | Không thỏa điều kiện nghiệp vụ |

Ví dụ là hướng dẫn mapping, chưa có chức năng tài khoản/chuyển tiền ở BL-002. Mỗi module phải đặc tả
catalog code và acceptance cases riêng; không dùng lại các code giao thức ở bảng trên cho lỗi nghiệp vụ.
Domain model thuần Java không phụ thuộc HTTP; adapter/application layer chuyển domain failure thành public code.

Lỗi 500/timeout không chứng minh giao dịch tương lai chưa commit. Luồng ghi tiền phải có idempotency
và API tra cứu trạng thái trước quyết định retry/reversal; correlation ID không cung cấp bảo đảm này.

## Swagger và cách thêm endpoint

1. Controller nằm trong `<module>.api`, gắn `@ApiErrorResponses` cho shared default/500 error schema.
2. Khai báo success response, request DTO/validation và lỗi nghiệp vụ cụ thể trên operation.
3. OpenAPI customizer bổ sung optional request correlation header và response header cho mọi response đã khai báo.
4. Có web contract tests cho case liên quan; nếu DTO sai phải chứng minh giá trị nhạy cảm không ra response.

Architecture test kiểm tra controller khai báo error annotation. Integration test đọc OpenAPI JSON để kiểm tra
schema `ApiProblem`, response headers và default error response thay vì chỉ kiểm tra Swagger UI tải được.

## Phạm vi còn lại

Advice xử lý exceptions đi qua Spring MVC khi response còn ghi được. Không tuyên bố có thể chuẩn hóa lỗi
trước servlet/filter chain, lỗi TLS/proxy hoặc response đã committed. BL-003 đã tích hợp security
entrypoint/access-denied handler và method-security exceptions với cùng contract 401/403;
xem [đặc tả IAM](iam.md). Lỗi của servlet container trước filter chain vẫn ngoài phạm vi.
Log lỗi bất ngờ hiện chỉ chứa loại exception và correlation ID, không chứa exception message/payload.
Khi bổ sung observability phải có redaction và propagation riêng, không tự thêm dữ liệu khách hàng vào log.

Nguồn thiết kế: [Spring MVC Problem Details / RFC 9457](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).
