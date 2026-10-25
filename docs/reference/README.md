# Tài liệu bootstrap gốc do owner cung cấp

Nguồn: [nhận xét của owner tại PR #2](https://github.com/ThanhDat19/app-bank/pull/2#issuecomment-6396794189),
file [bank-platform-bootstrap.zip](https://github.com/user-attachments/files/33923990/bank-platform-bootstrap.zip).
Nhập ngày 25/10/2026, theo yêu cầu bổ sung tài liệu phân tích cũ.

SHA-256 ZIP: `970c7a3037ad3ebed6bd8916fc52d49c13c253fef4cd620fe048920c25c1fbb1`.
Tám file Markdown dưới `bootstrap-v0.1/` giữ nguyên nội dung từ README và `docs/` của ZIP;
chỉ bỏ tiền tố thư mục `bank-platform/`. Code/cấu hình cũ không được chép đè lên dự án hiện tại.
Các file gốc là tài liệu lịch sử, không phải hướng dẫn vận hành đang có hiệu lực.

## Nội dung

- [README gốc](bootstrap-v0.1/README.md)
- [Project charter](bootstrap-v0.1/docs/PROJECT_CHARTER.md)
- [Roadmap gốc](bootstrap-v0.1/docs/plan/roadmap.md)
- [Đặc tả BL-001 gốc](bootstrap-v0.1/docs/plan/BL-001-bootstrap.md)
- [ADR về Ledger](bootstrap-v0.1/docs/adr/0001-ledger-consistency.md)
- [ADR về giao thức](bootstrap-v0.1/docs/adr/0002-transport-boundaries.md)
- [Definition of Done gốc](bootstrap-v0.1/docs/review/definition-of-done.md)
- [Local setup gốc](bootstrap-v0.1/docs/development/local-setup.md)

## Đối chiếu với phiên bản hiện tại

| Chủ đề | Tài liệu gốc | Đang áp dụng |
| --- | --- | --- |
| Mã tác vụ | BL-001 đến BL-039, phân bổ cũ | [Roadmap hiện tại](../project/roadmap.md); BL-002 = GitHub Issue #3 |
| Thứ tự an toàn | IAM ở S09, concurrency sau transfer | IAM trước ghi tiền; idempotency/locking cùng nghiệp vụ |
| Namespace/API metadata | `dev.banklab`, `name=bank-platform` | `vn.banklab.core`, `service=core-banking-service` |
| PostgreSQL port | Hướng dẫn BL-001 ghi 5433 | Mặc định 5432, cấu hình qua `.env` |
| Dependency | Bootstrap dự kiến Boot 4.1.1 | [ADR-002 hiện tại](../architecture/ADR-002-platform-baseline.md) |
| Kiểm chứng | Bản gốc chưa có Maven/Docker | BL-001 đã qua local tests và CI; owner merge PR #2 |
| Tính nhất quán Ledger | Một ACID boundary, append-only, outbox | Giữ trong [ADR-001 hiện tại](../architecture/ADR-001-core-boundary.md) |

ZIP chứa tài liệu bootstrap/định hướng kiến trúc, chưa chứa bộ BRD/SRS/FSD nghiên cứu đầy đủ.
Không suy diễn rằng những báo cáo đó đã được khôi phục từ attachment này.
