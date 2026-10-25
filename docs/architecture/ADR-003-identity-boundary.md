# ADR-003 — Identity provider và actor đã xác minh

- Ngày: 25/10/2026. Trạng thái: Đề xuất trong BL-003, chờ owner review.
- Issue: [#5](https://github.com/ThanhDat19/app-bank/issues/5).

## Quyết định

Keycloak quản lý đăng nhập và credentials. Core là OAuth2 Resource Server, chỉ nhận access token
qua `Authorization: Bearer`. Swagger dùng Authorization Code + PKCE S256 với public client;
core không nhận mật khẩu, không tự phát JWT và không tạo session đăng nhập.

Local có realm `bank-lab`, client `bank-swagger` và resource client `bank-api`.
Chỉ nhận RS256 từ JWKS đã cấu hình, kiểm tra issuer, audience `bank-api`, expiration/nbf,
subject không rỗng và claim payload `typ=Bearer` theo hợp đồng Keycloak. Đây là hợp đồng riêng
của IdP hiện tại; đổi IdP phải đánh giá claim/type và tests, không chỉ đổi URL.
HTTP chỉ được phép ở loopback với profile `local`; ngoài local bắt buộc cấu hình endpoint HTTPS.
JWKS URI trực tiếp giúp application khởi động không phụ thuộc discovery call. Nếu không lấy được
khóa cần thiết thì token không được chấp nhận; cache khóa do Spring/Nimbus quản lý.

Client roles `resource_access.bank-api.roles` chỉ cho phép CUSTOMER và OPERATOR.
Không tin realm roles, roles của client khác, `X-User-Id` hoặc `X-Roles`.
`CurrentActorProvider` là hợp đồng application; Spring Security/JWT nằm trong infrastructure.
Các module nghiệp vụ sau này dùng `(issuer, subject)` để tìm liên kết Customer trong DB và kiểm tra
ownership, không nhận customer ID do client cung cấp làm bằng chứng quyền sở hữu.

## Dữ liệu và ranh giới transaction

PostgreSQL core hiện chỉ có schema `banking` và Flyway V1; chưa có bảng Customer/Account.
Keycloak local dùng H2 development trong volume riêng `keycloak-data`. Một tài khoản IdP có thể
đăng nhập và gọi `/iam/me` dù chưa có hồ sơ ngân hàng. Kết quả này không chứng minh đã KYC,
đã mở tài khoản, có số dư hoặc được phép chuyển tiền. BL-004 sẽ đặc tả liên kết và các điều kiện đó.

Không seed khách hàng/số dư để làm IAM hoạt động. Không lưu password/token vào core DB hoặc Git.
Realm import không chứa users. Owner tạo user thử nghiệm qua Admin Console; script kiểm chứng tự
tạo một user tổng hợp rồi xóa trong `finally`, không in credentials hoặc token.

## Phương án đã cân nhắc

- Tự viết username/password và token service: làm tăng phạm vi bảo vệ credentials, reset password,
  session và rotation trước khi hoàn thành core; không chọn cho baseline.
- Mock JWT trong toàn bộ integration tests: không chứng minh chữ ký/JWKS/validation hoạt động;
  thay bằng RSA ký thật, JWKS HTTP và PostgreSQL Testcontainers. Thêm smoke test với Keycloak thật.
- Dùng subject như customer ID ngay: lẫn identity với quan hệ nghiệp vụ; không chọn.

## Hệ quả và nợ đã ghi nhận

Role gate theo URL là baseline. API nghiệp vụ phải bổ sung ownership, state rules và kiểm tra quyền
ở application/service; OPERATOR không tự động được phép mọi thao tác tài chính.
Offline JWT không thu hồi ngay sau logout/đổi role/khóa user. Local access token sống 300 giây,
validator dùng clock skew mặc định của Spring; đây không phải bảo đảm revocation tức thì.
MFA/step-up, production IdP database/HA/TLS, refresh-token policy cho ứng dụng thật, audit security
và threat model cần tác vụ riêng. `start-dev` và HTTP localhost chỉ phục vụ mô phỏng.

Nguồn: [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[Keycloak containers](https://www.keycloak.org/server/containers),
[Keycloak realm import](https://www.keycloak.org/server/importExport).
