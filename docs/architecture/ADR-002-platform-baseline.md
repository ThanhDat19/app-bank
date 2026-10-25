# ADR-002 — Baseline tái lập được, PostgreSQL và Swagger từ đầu

- Ngày: 25/10/2026
- Trạng thái: Đề xuất, chờ owner review PR BL-001

## Quyết định

Java 21, Maven Wrapper 3.9.11, Spring Boot 4.1.2, springdoc 3.2.0 và PostgreSQL 16.14-alpine.
Issue ban đầu ghi Boot 4.1.1; chọn patch 4.1.2 đã công bố thay cho patch cũ. Không sử dụng RC/SNAPSHOT
cho dependency nền tảng. Spring Boot BOM quản lý versions ngoài springdoc.

Flyway là nguồn thay đổi schema; Hibernate `ddl-auto=validate`, tắt Open Session in View và SQL init.
V1 chỉ tạo schema `banking`, không tạo số dư hay seed khách hàng. Các entity sau này map schema rõ ràng.
Migration đã merge và áp dụng phải bất biến; sửa bằng V tiếp theo, không bật Flyway clean.

Web slice test chạy không Docker. Full `verify` chạy PostgreSQL Testcontainers, Flyway và HTTP contract
thực, bao gồm Swagger assets/spec. Không dùng H2 làm bằng chứng tương thích PostgreSQL và không tự skip
integration tests khi Engine lỗi. Compose và tests dùng cùng phiên bản image.

Swagger và OpenAPI cùng bật với `local`, cùng tắt theo mặc định ở profile khác.
Khi có IAM, cấu hình docs theo vai trò. BL-001 chưa có API tài chính và chỉ bind localhost.
Chỉ expose Actuator health, không expose env/configprops hoặc database details.

## Hệ quả

Full verification cần Docker và mạng/cache cho image/dependencies; kiểm tra web nhanh dùng `test`.
BL-001 có integration smoke sớm hơn roadmap cũ để tránh bootstrap được đánh dấu đạt chỉ nhờ mock test.
Chọn image patch cụ thể giúp tái lập nhưng vẫn cần cập nhật định kỳ, không phải pin mãi mãi.

## Nguồn chính thức

- [Spring release listings](https://spring.io/blog/category/releases/) — kiểm tra patch GA tại thời điểm tạo baseline.
- [Springdoc compatibility matrix](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
- [Spring Boot Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Maven Wrapper](https://maven.apache.org/wrapper/)
