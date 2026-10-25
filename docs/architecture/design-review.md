# Rà soát thiết kế trước triển khai

Ngày 25/10/2026. Đánh giá dựa trên nội dung trao đổi được cung cấp và các file đã đọc của repository
mẫu; không có toàn văn báo cáo Deep Research hoặc bundle cũ trong workspace để xác nhận toàn bộ.
Kết luận: đủ cơ sở làm nền tảng; các quyết định tài chính dưới đây cần được đặc tả và nghiệm thu
trước khi phát triển luồng ghi tiền. Không coi kế hoạch kiến trúc là bằng chứng phần mềm đã chạy đúng.

## Kiểm tra repository tham khảo

Repository [chrisblakely01/java-spring-microservices](https://github.com/chrisblakely01/java-spring-microservices),
revision `33aaf18b5b12c41e2390420f50b4bd53b1c5b9e4`. Đây là ứng dụng quản lý bệnh nhân phục vụ khóa học.
Đã đọc tree và các file bên dưới; đây là review có trọng tâm, không phải audit toàn bộ repository.

| Bằng chứng | Nhận xét áp dụng cho ngân hàng | Cải thiện trong app-bank |
| --- | --- | --- |
| [PatientService.createPatient](https://github.com/chrisblakely01/java-spring-microservices/blob/33aaf18b5b12c41e2390420f50b4bd53b1c5b9e4/patient-service/src/main/java/com/pm/patientservice/service/PatientService.java) lưu repository, gọi Billing gRPC, gửi event | Không có transaction phân tán/recovery bảo đảm cả ba bước cùng hoàn tất trong luồng này | Core nội bộ ACID; Outbox; external workflow có trạng thái phục hồi |
| Cùng file: existsByEmail trước save | Hai request có thể cùng vượt kiểm tra exists; application check không thay unique constraint | Unique DB + xử lý conflict; khóa và idempotency cho lệnh tài chính |
| [KafkaProducer.sendEvent](https://github.com/chrisblakely01/java-spring-microservices/blob/33aaf18b5b12c41e2390420f50b4bd53b1c5b9e4/patient-service/src/main/java/com/pm/patientservice/kafka/KafkaProducer.java) gửi bất đồng bộ, try/catch quanh send | Không chờ/xử lý future; không có outbox trong đường code này; DB và broker có thể lệch | Outbox commit cùng DB, relay retry, metric backlog, consumer dedupe |
| Cùng producer mang name/email và log event khi có exception | Payload/log có thông tin cá nhân; sao chép sang banking sẽ cần kiểm soát riêng | Tối thiểu dữ liệu, định danh opaque, redaction và retention theo mục đích |
| [patient-service pom](https://github.com/chrisblakely01/java-spring-microservices/blob/33aaf18b5b12c41e2390420f50b4bd53b1c5b9e4/patient-service/pom.xml) dùng Boot 3.4.0, springdoc 2.7.0 và nhiều version gRPC/protobuf riêng | Không nâng một dependency lên Boot 4 rồi giữ nguyên toàn bộ thư viện cũ | Baseline BOM nhất quán, kiểm chứng integration khi thêm gRPC/Kafka |
| [JwtUtil](https://github.com/chrisblakely01/java-spring-microservices/blob/33aaf18b5b12c41e2390420f50b4bd53b1c5b9e4/auth-service/src/main/java/com/pm/authservice/util/JwtUtil.java) dùng HMAC, token 10 giờ | Hàm validate này chưa thể hiện issuer/audience, key rotation hay authorization tài khoản | OIDC provider và resource server, kiểm tra claims + ownership, negative tests |

Giữ cách học REST → gRPC → Kafka → Gateway/infra, nhưng thứ tự xây app-bank ưu tiên nghiệp vụ.
Bootstrap là mã mới theo yêu cầu ngân hàng, không copy bệnh nhân thành khách hàng hoặc billing thành ledger.

## Điểm yếu của kế hoạch ban đầu và bản điều chỉnh

| Điểm yếu | Quyết định cải thiện | Bằng chứng nghiệm thu sẽ cần |
| --- | --- | --- |
| IAM đến quá muộn sau chuyển tiền | IAM/ownership trước API ghi tiền, mọi giai đoạn chưa IAM chỉ localhost + synthetic data | 401/403, cross-customer denied, sai issuer/audience bị từ chối |
| Idempotency/concurrency để sau chức năng transfer | Thiết kế idempotency từ cash-in, triển khai khóa + replay cùng transfer; sprint sau chỉ tăng stress coverage | Cùng key cùng payload trả một kết quả; đổi payload conflict; chạy đồng thời không âm tiền |
| Reversal chưa có tác vụ riêng | Ledger append-only và reversal được đặt trong mốc core | Không sửa/xóa posting đã POSTED; đảo tối đa một lần theo policy đã chốt |
| Chỉ nói tổng posting bằng 0 | Cân bằng theo journal + currency, account type và debit/credit rõ ràng; posted journal có ít nhất hai postings | DB deferred constraint hoặc posting procedure có quyền ghi giới hạn, không chỉ CHECK trên một dòng |
| Số dư cached có thể lệch ledger | Ledger là nguồn gốc, balance projection cập nhật cùng transaction; đối soát độc lập | Sum postings khớp projection, phát hiện được dữ liệu cố tình sai |
| Bootstrap thiếu runtime evidence | PostgreSQL integration smoke từ BL-001 | Flyway, health, Swagger chạy qua HTTP thực |
| Chia microservices theo danh từ quá sớm | Một core deployable; chỉ tách khi có nhu cầu và ADR | Transaction boundary được giữ, không gọi mạng trong khóa DB |
| Quy tắc đóng băng/hạn mức/phí chưa chốt | Chốt state matrix, hold expiry và nguồn thời gian trước tác vụ tương ứng | Test cạnh tranh freeze/transfer, capture/release và reset hạn mức |
| Chưa có số đo NFR | S17 đo baseline, owner chốt hardware/workload/SLO; RPO/RTO được thử ở S18 | Báo cáo thực đo và restore drill, không tự tuyên bố đạt production |

## Các bất biến thiết kế cần giữ

1. Tiền dùng `BigDecimal`/PostgreSQL NUMERIC với scale và rounding policy theo currency; VND giai đoạn đầu
   là số nguyên dương, không dùng float/double. Không tự suy diễn định mức pháp lý hoặc hạn mức ngân hàng thật.
2. Journal POSTED phải cân bằng debit/credit theo currency, không xóa/sửa postings; reversal là journal mới
   có liên kết gốc. Tài khoản khách hàng và tài khoản đối ứng có loại kế toán rõ ràng.
3. Cash-in mô phỏng phải có tài khoản nguồn đối ứng, authorization và audit; không UPDATE balance để tạo tiền.
4. Available = posted balance trừ active holds theo quy ước tài khoản; không âm available khi không cấp overdraft.
5. Financial command có idempotency scope theo actor + operation + key, request hash và unique constraint.
   Dedupe event riêng theo consumer + event_id. Không dùng hết hạn cache để cho phép ghi trùng nghiệp vụ.
6. Timeout không đồng nghĩa thất bại. Có transferId và truy vấn trạng thái; không tự reversal một giao dịch
   có trạng thái bên ngoài chưa xác định. Hạch toán nguồn chính thức quyết định kết quả.
7. Audit, operational log và event stream phục vụ các mục tiêu khác nhau; không đặt secret/PII đầy đủ trong chúng.

## Các quyết định nghiệp vụ chốt trước tác vụ phụ thuộc

| Quyết định | Đề xuất mô phỏng | Owner chốt tại |
| --- | --- | --- |
| Phạm vi sản phẩm | Cá nhân, một currency VND, nội bộ trước | BL-003/004 |
| KYC | Dữ liệu giả, trạng thái pending/verified/rejected, chưa tích hợp eKYC thật | BL-004 |
| Account states | Active/frozen/closed; frozen chặn debit, chính sách credit cần chốt | BL-005 |
| Fees và limits | Bảng cấu hình có effective time/version, không hardcode hạn mức pháp luật | BL-011 |
| Reversal | Toàn phần một lần cho MVP; quy tắc thiếu tiền/tài khoản bị khóa phải rõ | BL-010 |
| External UNKNOWN | Giữ trạng thái/hold theo policy, query và reconciliation trước quyết định tiếp | BL-018 |

Đây là đề xuất cần review theo từng Issue. Không bắt đầu nghiệp vụ phụ thuộc chỉ vì lịch dự kiến đã đến.
