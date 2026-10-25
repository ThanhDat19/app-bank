# ADR-001 — Core dùng chung transaction, tích hợp theo nhu cầu

- Ngày: 25/10/2026
- Trạng thái: Đề xuất, chờ owner review PR BL-001

## Bối cảnh và quyết định

Mục tiêu là chứng minh tính đúng đắn nghiệp vụ tài chính trong một dự án có thể vận hành bởi một người.
Bắt đầu với một deployable `core-banking-service`, tổ chức package theo nghiệp vụ.
Customer (ban đầu), Account, Ledger, Transfer, Idempotency và Outbox dùng chung PostgreSQL.
Mỗi module sở hữu service/repository; module khác gọi application API, không truy cập repository tùy tiện.

Một chuyển tiền nội bộ ghi debit/credit, journal/postings, trạng thái giao dịch, idempotency và outbox
trong cùng transaction khi các thành phần đó được triển khai. Khóa tài khoản theo thứ tự ổn định;
đọc lại điều kiện sau khi lấy khóa; cập nhật available balance phải nguyên tử với posting/hold.
Check qua REST/gRPC trước đó không thay thế kiểm tra cuối trong transaction.

## Giao thức

| Nhu cầu | Cơ chế | Ràng buộc |
| --- | --- | --- |
| Web/mobile, tra cứu và ra lệnh | REST + OpenAPI | Có authorization, validation và idempotency với lệnh tài chính |
| Risk decision ở service độc lập | gRPC unary | Deadline, quyết định xử lý timeout, không retry lệnh ghi tiền mù quáng |
| Hậu kỳ cho nhiều bên | Kafka + Outbox | Relay retry, consumer dedupe, ordering theo aggregate khi cần |
| Theo dõi trạng thái | SSE (hoặc WebSocket khi cần hai chiều) | Client reconnect rồi truy vấn trạng thái chính thức |
| Luồng giá nội bộ nhiều bản tin | gRPC server streaming, giai đoạn tùy chọn | Flow control, reconnect, snapshot/resume do ứng dụng thiết kế |

Không đặt gRPC giữa Account và Transfer chỉ để thêm công nghệ; không tách Ledger ra khỏi transaction
hiện tại. Notification/Risk là ứng viên tách service. Việc tách Customer sau này cần ADR về dữ liệu
snapshot, ownership và thời điểm kiểm tra KYC, không di chuyển máy móc các foreign key qua mạng.

## Hệ quả và phương án khác

Ưu điểm: kiểm chứng ACID và concurrency dễ hơn; build, debug và review nhỏ.
Chi phí: chưa scale/deploy độc lập từng module; phải quản lý ranh giới package.
Phương án nhiều DB từ đầu bị hoãn vì cần workflow phân tán, recovery và đối soát phức tạp hơn.
External payment dùng state machine/holds/compensation riêng; không giả định ACID xuyên ngân hàng.

Khi tách service, đo độ trễ/tải, chỉ ra nhu cầu triển khai độc lập và lập ADR mới trước khi thay đổi.
