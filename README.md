# app-bank — Bank Lab

Hệ thống ngân hàng mô phỏng dùng Java 21, Spring Boot, PostgreSQL, Flyway và Swagger/OpenAPI.
Phát triển theo từng tác vụ BL, commit nhỏ và PR do chủ repository review/merge.

## Bắt đầu

Yêu cầu: JDK 21, Docker Desktop chạy Linux containers, Git. Maven Wrapper nằm trong repo.

```powershell
Copy-Item .env.example .env
# Sửa POSTGRES_PASSWORD và KEYCLOAK_ADMIN_PASSWORD trong .env bằng mật khẩu local riêng.
. .\scripts\Use-LocalEnvironment.ps1
docker compose --profile iam up -d --wait postgres keycloak
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

`test` chạy web slice test không cần Docker; `verify` chạy thêm integration tests trên PostgreSQL
Testcontainers riêng biệt. Docker không hoạt động thì `verify` phải thất bại, không bỏ qua test.
Xem [hướng dẫn môi trường](docs/development/local-setup.md) nếu dùng IntelliJ.

| URL | Mục đích |
| --- | --- |
| http://localhost:8080/swagger-ui.html | Swagger UI, bật với profile `local` |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |
| http://localhost:8080/v3/api-docs.yaml | OpenAPI YAML |
| http://localhost:8080/api/v1/system/info | `core-banking-service`, `SIMULATION` |
| http://localhost:8080/actuator/health | Trạng thái ứng dụng và kết nối DB |
| http://localhost:8080/api/v1/iam/me | Danh tính đã xác minh; cần CUSTOMER hoặc OPERATOR access token |
| http://localhost:8180/admin/ | Keycloak local; tạo user và gán client role để thử đăng nhập |

## Tài liệu dự án

- [Đánh giá thiết kế và repository mẫu](docs/architecture/design-review.md)
- [ADR-001: Ranh giới core và các giao thức](docs/architecture/ADR-001-core-boundary.md)
- [ADR-002: Nền tảng, migrations, Swagger và kiểm thử](docs/architecture/ADR-002-platform-baseline.md)
- [Kế hoạch 20 sprint, tác vụ và phân công](docs/project/roadmap.md)
- [Git workflow và Definition of Done](docs/project/delivery-workflow.md)
- [BL-001: Phạm vi và phiếu review](docs/reviews/BL-001.md)
- [Tài liệu bootstrap gốc owner đính kèm](docs/reference/README.md)
- [BL-002: REST errors và correlation](docs/api/conventions.md)
- [Quy tắc module được kiểm tra bằng ArchUnit](docs/architecture/module-boundaries.md)
- [BL-002: Kết quả kiểm chứng và phiếu review](docs/reviews/BL-002.md)
- [BL-003: Cách tạo user, đăng nhập Swagger và kiểm chứng PKCE](docs/development/local-iam.md)
- [BL-003: API và quy tắc IAM](docs/api/iam.md)
- [ADR-003: Ranh giới identity và dữ liệu ngân hàng](docs/architecture/ADR-003-identity-boundary.md)
- [BL-003: Bằng chứng kiểm thử và phiếu review](docs/reviews/BL-003.md)
- [BL-004: Đặc tả Customer/KYC, ownership và concurrency](docs/api/customer-kyc.md)
- [BL-004: Thử luồng Customer → Operator trên Swagger](docs/development/local-customer-kyc.md)
- [BL-004: Bằng chứng kiểm thử và phiếu review](docs/reviews/BL-004.md)
- [BL-005: Đặc tả Account lifecycle và closure guard](docs/api/account-lifecycle.md)
- [BL-005: Thử mở/freeze/unfreeze/close trên Swagger](docs/development/local-account-lifecycle.md)
- [BL-005: Bằng chứng kiểm thử và phiếu review](docs/reviews/BL-005.md)
- [BL-006: Đặc tả Ledger và posted balance](docs/api/ledger.md)
- [ADR-004: Immutable ledger, locks và balance](docs/architecture/ADR-004-ledger-posting.md)
- [BL-006: Thử provisioning/balance trên Swagger](docs/development/local-ledger.md)
- [BL-006: Bằng chứng kiểm thử và phiếu review](docs/reviews/BL-006.md)
- [BL-007: Đặc tả cash-in và idempotency](docs/api/cash-in.md)
- [BL-007: Thử nạp tiền mô phỏng trên Swagger](docs/development/local-cash-in.md)
- [BL-007: Bằng chứng kiểm thử và phiếu review](docs/reviews/BL-007.md)
- [BL-008: Đặc tả internal transfer, locks và idempotency](docs/api/internal-transfer.md)
- [BL-008: Thử chuyển tiền nội bộ trên Swagger](docs/development/local-internal-transfer.md)
- [BL-008: Bằng chứng kiểm thử và phiếu review](docs/reviews/BL-008.md)
- [BL-010: Đặc tả hoàn giao dịch toàn phần](docs/api/reversal.md)
- [BL-010: Thử reversal trên Swagger](docs/development/local-reversal.md)
- [BL-009/010: Concurrency, lost response và phiếu review](docs/reviews/BL-009-010.md)

BL-001 cung cấp nền tảng; BL-002 bổ sung hợp đồng lỗi, correlation và kiểm tra ranh giới module.
BL-003 bổ sung xác thực qua Keycloak và actor context; `/iam/me` đọc JWT đã xác minh.
BL-004 thêm hai bảng Customer/KYC qua Flyway V2. CUSTOMER tự tạo/xem hồ sơ; OPERATOR khác identity
quyết định KYC mô phỏng. Xem hướng dẫn Customer/KYC để thử bằng hai user trên Swagger.
BL-005 thêm tài khoản VND, ownership và open/freeze/unfreeze/close với history.
BL-006 thêm Ledger VND bất biến, internal posting contract, API liên kết và đọc số dư POSTED dạng string.
Account đã provision chỉ đóng khi posted balance bằng 0.
BL-007 thêm OPERATOR cash-in mô phỏng, request hash/key và receipt bền vững cùng transaction với Ledger;
retry cùng key/payload không tạo tiền lần hai. BL-008 thêm CUSTOMER chuyển VND nội bộ với source ownership,
ordered pair locks, no overspend và receipt/idempotency nguyên tử. Chưa có available balance.
BL-009/010 bổ sung concurrency/lost-response recovery và OPERATOR full reversal một lần bằng journal mới;
original bất biến, giữ điều kiện ACTIVE và đủ số dư.
Chỉ dùng dữ liệu tổng hợp; dự án chưa có chứng nhận
nghiệp vụ, an ninh hay vận hành cho ngân hàng thực tế.
