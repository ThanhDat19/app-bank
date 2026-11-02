# BL-008 — Thử chuyển tiền nội bộ trên Swagger

Chạy môi trường theo [local setup](local-setup.md), build bản mới và mở Swagger profile `local`.
Flyway áp dụng V6 khi app khởi động; giữ V1–V5 và tiền mô phỏng hiện có, không cần reset volume.

1. Chuẩn bị hai CUSTOMER A/B, mỗi user có KYC VERIFIED và account VND ACTIVE theo các guide trước.
2. Mỗi owner provision Ledger account của mình theo [BL-006](local-ledger.md). Ghi lại UUID A/B.
3. OPERATOR dùng [cash-in BL-007](local-cash-in.md) nạp `"500000"` vào A, với key cash-in mới.
4. Authorize bằng CUSTOMER A. Trong **Internal Transfers**, POST `/api/v1/transfers` với key mới `demo-transfer-008-a`:

   ```json
   {"sourceAccountId":"<UUID A>","destinationAccountId":"<UUID B>","amount":"100000","currency":"VND"}
   ```

5. Nhận 201 POSTED receipt; giữ `id`, `journalId`, `postedAt`, `Location`. GET receipt bằng A trả cùng body.
6. A/B xem own posted balance lần lượt `"400000"`/`"100000"`. Không có phí.
7. POST lại cùng A/key/body: cùng receipt 201, balances không đổi. Khi timeout giữ key/body cũ để retry.
8. Cùng key đổi amount `"100001"`: 409 IDEMPOTENCY_CONFLICT; không debit/credit thêm.
9. Key mới, amount vượt balance A: 422 LEDGER_INSUFFICIENT_FUNDS; B không được credit một phần.
10. Authorize bằng B rồi cố dùng source UUID A: 404 ACCOUNT_NOT_FOUND. GET receipt của A bằng B cũng 404.
11. OPERATOR-only POST/GET transfer: 403. Dual-role vẫn phải sở hữu source.
12. Freeze A hoặc B qua OPERATOR: command mới trả 409 ACCOUNT_STATE_INVALID; replay key cũ vẫn trả receipt cũ.
13. Source=destination trả 422; amount number thay string, `"0"`, `"1.5"`, USD, thiếu key trả 400.

Destination là UUID bank account, không phải số account hoặc ledger account ID. Không có recipient directory/PII lookup.
Đổi key là transfer mới, có thể debit tiếp; không dùng key mới để thử lại một response bị mất.
Không có public reversal/rút tiền trong BL-008. Có thể B chuyển lại A bằng một command mới của B khi đủ điều kiện;
đó là giao dịch độc lập, không phải reversal của journal trước.

Full rules/errors/locks/recovery tại [đặc tả](../api/internal-transfer.md); kết quả kiểm thử tại [phiếu review](../reviews/BL-008.md).
