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

BL-001 cung cấp nền tảng; BL-002 bổ sung hợp đồng lỗi, correlation và kiểm tra ranh giới module.
BL-003 bổ sung xác thực qua Keycloak và actor context. Core DB chưa có bảng nghiệp vụ; đăng nhập dùng
user trong Keycloak, `/iam/me` đọc JWT đã xác minh. Xem hướng dẫn IAM trước khi thử Swagger.
Customer, Account, Ledger và Transfer
được triển khai trong các PR tiếp theo sau review. Chỉ dùng dữ liệu tổng hợp; dự án chưa có chứng nhận
nghiệp vụ, an ninh hay vận hành cho ngân hàng thực tế.
