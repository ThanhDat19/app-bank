# BL-010 — Thử hoàn giao dịch trên Swagger

Build bản mới, chạy profile local theo [local setup](local-setup.md). Flyway V7 không seed/reset dữ liệu.
BL-008/PR #21 là dependency, owner merge trước PR reversal. Chỉ dùng dữ liệu/tiền mô phỏng.

1. Chuẩn bị CUSTOMER A/B, accounts VND ACTIVE và Ledger provisioned.
2. OPERATOR [cash-in](local-cash-in.md) `"500000"` vào A, lưu receipt/key/journalId.
3. A [transfer](local-internal-transfer.md) `"100000"` tới B; balances 400000/100000, lưu journalId transfer.
4. Authorize OPERATOR, **Full Reversals**, POST key mới `demo-reversal-010-a`:

   ```json
   {"originalJournalId":"<journalId transfer>","reason":"OPERATOR_ERROR"}
   ```

5. Nhận201 receipt có originalJournalId/journalId mới; balances 500000/0. GET bằng cùng operator.
6. Cùng key/body/operator retry: cùng receipt 201, balances không đổi. Khác key cho original này409 REVERSAL_ALREADY_EXISTS.
7. Cùng key đổi reason DUPLICATE_PROCESSING: 409 IDEMPOTENCY_CONFLICT; operator khác GET receipt tr? 404.
8. Original transfer GET/retry vẫn trả receipt POSTED gốc; reversal không sửa lịch sử.
9. Hoàn journalId cash-in 500000 ban đầu: A về0, clearing giảm tương ứng; owner có thể close A.
10. Reversal đã commit replay được sau close; command mới không bypass FROZEN/CLOSED.
11. Account demo khác cash-in 100 rồi transfer 80 đi: hoàn cash-in 100 trả422 insufficient, không đảo một phần.
    Bổ sung tiền hợp lệ rồi retry đúng key/body nếu muốn hoàn toàn phần.

Request thêm amount/postings/actor trả400; CUSTOMER gọi reversal tr? 403. Không hoàn REVERSAL/ADJUSTMENT/internal
journal không public receipt (422). OPERATOR trực tiếp hoàn theo policy mô phỏng; maker-checker ở BL-019.
Không đổi key sau timeout. [Rules/errors/locks](../api/reversal.md) · [Evidence](../reviews/BL-009-010.md).
