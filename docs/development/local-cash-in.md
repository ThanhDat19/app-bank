# BL-007 — Thử cash-in mô phỏng trên Swagger

Chạy môi trường theo [local setup](local-setup.md), build bản mới rồi mở Swagger profile `local`.
V5 được Flyway áp dụng khi ứng dụng khởi động; không cần reset dữ liệu hoặc volume.
Đây là thao tác tạo tiền mô phỏng có lưu dữ liệu. Chỉ dùng dữ liệu tổng hợp.

1. CUSTOMER tạo profile → OPERATOR khác duyệt KYC → CUSTOMER mở account VND theo các guide trước.
2. CUSTOMER gọi `POST /api/v1/ledger/accounts/{accountId}/provision`; balance ban đầu `"0"`.
3. Authorize lại bằng OPERATOR. Trong **Simulated Cash-in**, POST với key mới `demo-cash-in-007-a` và body:

   ```json
   {"accountId":"<UUID bank account ACTIVE>","amount":"500000","currency":"VND"}
   ```

4. Nhận 201, ghi lại receipt `id`, `journalId`, `postedAt`, `Location`. GET receipt bằng cùng operator trả cùng body.
5. POST lại **cùng key/body/operator**: vẫn 201 và cùng receipt. Balance chỉ tăng một lần thành `"500000"`.
6. Cùng key, amount `"500001"`: 409 IDEMPOTENCY_CONFLICT. Không có tiền mới.
7. Đổi key là yêu cầu nạp mới, làm balance tăng thêm. Khi timeout, giữ key/body cũ để retry.
8. Freeze account theo guide BL-005. Replay key đã thành công vẫn trả receipt cũ; key mới trả 409 ACCOUNT_STATE_INVALID.
9. GET receipt bằng operator identity khác trả 404; CUSTOMER không gọi được cash-in/receipt (403).
10. Thử amount number `500000`, `"1.5"`, `"0"`, currency USD, thiếu key: 400, không phát sinh tiền.

Không thể close account có balance khác 0. BL-007 chưa có rút tiền/reversal: không sửa/xóa postings hoặc receipt
để đưa balance về 0. Dùng account mô phỏng mới cho demo closure, hoặc đợi use case reversal tương ứng.
Provision đã có thể thực hiện bởi owner ở BL-006; OPERATOR cash-in không provision thay CUSTOMER.

HTTP/Swagger contract và các mã lỗi đầy đủ tại [đặc tả cash-in](../api/cash-in.md).
