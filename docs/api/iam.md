# BL-003 — Đặc tả IAM baseline

## Use case và business rules

UC-IAM-01: Người thử nghiệm đã có user và client role trong Keycloak mở Swagger, chọn Authorize,
đăng nhập tại IdP, rồi gọi API bằng access token. Core xác minh token trước khi vào controller.
Không có `POST /login`, đăng ký khách hàng hoặc lưu mật khẩu tại core.

| Rule | Điều kiện và kết quả |
| --- | --- |
| IAM-01 | Không có bearer token hoặc token không hợp lệ → 401 UNAUTHENTICATED |
| IAM-02 | Token hợp lệ nhưng thiếu role cho phép → 403 ACCESS_DENIED |
| IAM-03 | Chỉ role CUSTOMER/OPERATOR của client audience cấu hình được ánh xạ thành quyền |
| IAM-04 | Actor lấy từ SecurityContext của JWT đã xác minh; header tự khai không thay đổi actor |
| IAM-05 | Danh tính ổn định là cặp issuer + subject; không dùng email/username làm khóa ownership |
| IAM-06 | Có danh tính IdP chưa đồng nghĩa có Customer/KYC/Account; nghiệp vụ sau phải kiểm tra DB |
| IAM-07 | API không redirect sang trang login, không tạo HTTP session và không cache `/me` |

## API contract

`GET /api/v1/iam/me`, không request body. Header:

```http
Authorization: Bearer <access-token>
X-Correlation-ID: review-003
```

HTTP 200, `Cache-Control: no-store`, `X-Correlation-ID: review-003`:

```json
{
  "issuer": "http://localhost:8180/realms/bank-lab",
  "subject": "1d5e085e-4f3a-4c2b-b2dc-915b16b5ca10",
  "roles": ["CUSTOMER"]
}
```

Subject là ví dụ; user thật có ID riêng. Roles trả theo thứ tự ổn định, chỉ các role được hỗ trợ.
Không trả email, mật khẩu hoặc JWT. 401/403 dùng [ApiProblem](conventions.md), `errors: []`,
correlation header/body trùng nhau, `Cache-Control: no-store`; 401 thêm `WWW-Authenticate: Bearer`.
Không trả lý do validation nội bộ. Authentication/authorization exception từ method security cũng
dùng contract này thay vì bị catch-all chuyển thành 500.

## Access matrix

| Route | Anonymous | CUSTOMER | OPERATOR |
| --- | --- | --- | --- |
| GET `/api/v1/system/info` | Cho phép | Cho phép | Cho phép |
| GET health/liveness/readiness | Cho phép nếu endpoint tồn tại | Cho phép | Cho phép |
| GET Swagger/OpenAPI ở local | Cho phép | Cho phép | Cho phép |
| `/api/v1/iam/me` | 401 | 200 | 200 |
| `/api/v1/operations/**` | 401 | 403 | Qua URL gate, còn tùy API/rules |
| `/api/v1/**` còn lại | 401 | Qua URL gate | Qua URL gate |
| Route ngoài allowlist/API | Từ chối | 403 | 403 |

Token sai vẫn bị 401 kể cả khi gọi public route có gửi bearer token. Ngoài local, docs mặc định tắt;
nếu bật thủ công thì security gate yêu cầu OPERATOR. Chưa có operations API thực trong BL-003.
Route chưa triển khai bên trong vùng API trả 404 sau khi caller vượt qua security gate.

## Acceptance và test mapping

| Case | Bằng chứng |
| --- | --- |
| RS256 đúng → issuer/subject/roles; header giả bị bỏ qua | `BootstrapIT.verifiedActorComesFromTokenAndNotSpoofedHeaders` |
| Malformed/unsigned/wrong key/RS512/issuer/audience/exp/nbf/sub/type | `BootstrapIT.untrustedTokensAreRejectedWithoutDetails` (14 cases) |
| Role thiếu/sai client/sai shape/không hỗ trợ/chỉ realm role | `BootstrapIT.rolesFromOtherClientsOrRealmDoNotGrantAccess` (5 cases) |
| Operator URL gate và method security, exception trong MVC | `BootstrapIT.operatorPolicyAndMethodSecurityHaveConsistent403` |
| Thiếu token, header giả, no session/no redirect, fail closed | Hai tests missingToken và protectedAndUnmappedRoutes |
| Trust configuration bắt buộc; HTTP chỉ local loopback | `IamConfigurationTest` |
| OpenAPI OAuth code flow, 401 schema, Swagger PKCE initializer | `BootstrapIT.servesSwaggerAssetsAndGeneratedApiContract` |
| Keycloak thật: customer/operator, ID token, PKCE, password grant | `scripts/verify-local-oidc.py` chạy thủ công trên local |

Test JWT ký thật bằng khóa ephemeral; không tắt security trong full integration tests.
Các web slice BL-002 dùng permit-all test configuration để kiểm tra riêng serialization của MVC;
chúng không phải bằng chứng API production được bảo vệ. Không đóng gói probe endpoints trong JAR.
