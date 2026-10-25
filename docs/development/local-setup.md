# Môi trường phát triển

## Windows / PowerShell

1. Cài JDK 21, Git và Docker Desktop; mở Docker Desktop, chờ Engine sẵn sàng.
2. Mở repository bằng IntelliJ, chọn Project SDK 21, Maven dùng Wrapper.
3. Chạy tại thư mục gốc:

```powershell
java -version
docker info
Copy-Item .env.example .env
```

Thay mật khẩu ví dụ trong `.env`. File này bị Git ignore. Script chỉ hỗ trợ dạng `KEY=value`
không dấu nháy, không multiline; dùng mật khẩu local gồm chữ/số. Không dùng mật khẩu thật ở dịch vụ khác.
Sau đó:

```powershell
. .\scripts\Use-LocalEnvironment.ps1
docker compose up -d --wait postgres
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

Dot-source bằng dấu chấm đầu dòng để giữ biến trong phiên PowerShell đang dùng.
Docker Compose đọc `.env`; Spring Boot không tự đọc `.env`, nên cần script ánh xạ sang `DB_*`.
Không đặt mật khẩu vào tham số dòng lệnh hoặc commit run configuration có secret.

Ứng dụng và PostgreSQL chỉ bind loopback. Swagger bật ở `local`, mặc định tắt ở profile khác.
Health chỉ trả trạng thái, không lộ thông tin datasource. Địa chỉ mặc định là cổng 8080 và DB 5432.

## IntelliJ Run Configuration

Main class `vn.banklab.core.CoreBankingApplication`, JRE 21, profile `local`.
Thiết lập `DB_URL=jdbc:postgresql://localhost:5432/bank_lab`, `DB_USERNAME=bank_lab`,
`DB_PASSWORD=<mật khẩu .env>` trong cấu hình local không share. Có thể chạy từ Terminal đã nạp script
thay vì cấu hình secret trong IDE. Maven tool window dùng JDK 21 và Wrapper.

## Các mức kiểm tra

| Lệnh | Kiểm tra | Docker |
| --- | --- | --- |
| `.\mvnw.cmd test` | Web contract slice | Không |
| `.\mvnw.cmd clean verify` | Unit + đóng gói + Flyway/PostgreSQL + HTTP/Swagger | Có |
| `docker compose ps` | Database local | Có |
| `Invoke-RestMethod http://localhost:8080/actuator/health` | DB và ứng dụng local | Có |

Failsafe chạy `*IT`; Surefire chạy `*Test`. CI gọi `clean verify`, không skip Docker tests.
Testcontainers tạo DB và credentials dùng tạm, không dùng dữ liệu volume local.
Linux/macOS dùng `./mvnw`, tự export `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_PROFILES_ACTIVE=local`.

## Dừng và xử lý lỗi

- Ctrl+C dừng ứng dụng. `docker compose stop postgres` dừng DB, giữ dữ liệu.
- Không xóa volume để chữa migration trên DB đang cần giữ dữ liệu. Tạo migration sửa tiếp theo.
- Nếu đổi mật khẩu `.env` sau khi volume được khởi tạo, PostgreSQL không tự cập nhật mật khẩu đã lưu.
  Đổi bằng công cụ quản trị local hoặc dùng một volume mô phỏng mới sau khi quyết định bỏ dữ liệu cũ.
- Nếu cổng 5432 bận, đổi `POSTGRES_PORT` trong `.env`, nạp lại script và chạy Compose.
- Nếu `DB_PASSWORD` chưa resolve, nạp script ở cùng terminal chạy Maven.
- Nếu Docker API báo lỗi, kiểm tra Desktop/WSL2 và quyền truy cập Engine trước khi chạy `verify`.
- Wrapper lần đầu cần mạng đến Maven Central; có thể đặt `JAVA_HOME` tới JDK 21 nếu Java không được tìm thấy.

## Hợp đồng bootstrap

`GET /api/v1/system/info` trả HTTP 200, JSON:

```json
{"service":"core-banking-service","mode":"SIMULATION"}
```

Đây là metadata, không xác nhận sẵn sàng giao dịch. Health mới kiểm tra database.
Flyway V1 tạo schema `banking`; bảng nghiệp vụ sẽ có migrations riêng theo từng PR.
Hibernate chỉ `validate`, không tạo/sửa schema tự động. Không có dữ liệu tiền hoặc số dư khởi tạo.

## BL-002: Theo dõi lỗi khi thử API

Swagger có optional header `X-Correlation-ID`. Có thể nhập `review-local-002` để đối chiếu response header
và log MDC. Mọi request không gửi ID sẽ được cấp UUID; ID này không thay idempotency key.
Để xem Problem Details mà không cần API demo, gọi một route không tồn tại:

```powershell
curl.exe -i -H "X-Correlation-ID: review-local-002" http://localhost:8080/api/v1/unknown
```

Kỳ vọng HTTP 404, `application/problem+json`, `code=RESOURCE_NOT_FOUND`, cùng correlation ID trong
header và body. Xem [hợp đồng đầy đủ](../api/conventions.md).
