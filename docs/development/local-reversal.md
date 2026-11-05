# BL-010 — Thử hoàn giao dịch trên Swagger

Build bản mới, chạy profile local theo [local setup](local-setup.md). V16 bắt buộc maker-checker,
không seed/reset dữ liệu. Cần hai OPERATOR khác identity; chỉ dùng dữ liệu/tiền mô phỏng.

1. Chuẩn bị CUSTOMER A/B, accounts VND ACTIVE và Ledger provisioned.
2. OPERATOR [cash-in](local-cash-in.md) `"500000"` vào A, lưu receipt/key/journalId.
3. A [transfer](local-internal-transfer.md) `"100000"` tới B; balances 400000/100000, lưu journalId transfer.
4. Authorize OPERATOR A, **Maker-checker**, POST `/api/v1/operations/requests`, key mới `demo-reversal-019-a`:

   ```json
   {"type":"REVERSAL","targetId":"<journalId transfer>","reason":"OPERATOR_ERROR"}
   ```

5. Nhận201 PENDING operation, balances chưa đổi. B approve operation id với `{"expectedVersion":0}`:
   200 APPROVED + result receipt có originalJournalId/journalId mới; balances500000/0.
6. B retry approve: cùng receipt200, balances không đổi. A create cùng key/body trả current operation201.
   Intent khác cho original này có thể tạo, nhưng approve bị409 REVERSAL_ALREADY_EXISTS.
7. A cùng key đổi reason DUPLICATE_PROCESSING:409 IDEMPOTENCY_CONFLICT. B có thể GET reversal receipt,
   A đọc receipt qua operation result; operator khác GET reversal riêng404.
8. Original transfer GET/retry vẫn trả receipt POSTED gốc; reversal không sửa lịch sử.
9. Tạo/duyệt intent mới hoàn journalId cash-in500000 ban đầu: A về0, clearing giảm tương ứng; owner có thể close A.
10. APPROVED replay được sau close bằng đúng checker/id; command mới không bypass FROZEN/CLOSED.
11. Account demo khác cash-in 100 rồi transfer 80 đi: hoàn cash-in 100 trả422 insufficient, không đảo một phần.
    Bổ sung tiền hợp lệ rồi retry approve đúng operation/checker nếu muốn hoàn toàn phần. Intent vẫn PENDING.

Request thêm amount/postings/actor trả400; CUSTOMER gọi reversal tr? 403. Không hoàn REVERSAL/ADJUSTMENT/internal
journal không public receipt (422 khi approve). Tự duyệt422; legacy direct POST409 OPERATION_APPROVAL_REQUIRED.
Không đổi key sau timeout create; giữ id/checker khi timeout approve.
[Maker-checker/recovery](../api/maker-checker.md) · [Rules/errors/locks](../api/reversal.md).
