# BL-011b — Hạn mức và phí chuyển tiền mô phỏng

Phạm vi: CUSTOMER internal transfer và full hold capture. Mỗi account nguồn có thể có một policy
trong `banking.outgoing_policies`. Chưa có API quản trị policy; không nhận limit/fee/timezone từ request chuyển tiền.
Các giá trị sau là policy mô phỏng có thể review, không phải biểu phí ngân hàng thực.

## Quy tắc

- Mặc định không có policy riêng: phí 0, transaction/daily limit `99999999999999999999` VND,
  timezone `Asia/Ho_Chi_Minh`. Đây là trần kỹ thuật, không phải hạn mức thương mại được chốt.
- Tổng chi `gross = principal + flat fee`. Cả hạn mức mỗi giao dịch và hạn mức ngày đều tính gross.
  Source account là phạm vi hạn mức, không gộp nhiều tài khoản của một customer.
- Ngày nghiệp vụ lấy từ `principal.posted_at AT TIME ZONE policy.timezone`, sau khi khóa cả pair accounts.
  Tổng usage theo `(source account, business date)` từ các assessment bất biến. Không cần job reset lúc nửa đêm.
- Retry cùng idempotency key hoặc capture cùng holdId trả receipt/journal cũ, không đánh giá policy hoặc thu phí lại.
  Thay policy không thay receipt/assessment cũ. Reversal hoàn principal; **phí không hoàn và usage không được khôi phục**.
- Hold place chỉ giữ principal. Capture cần thêm available balance cho phí bên ngoài hold đang capture.
  Release/expiry không thu phí và không sử dụng hạn mức. Policy được đánh giá tại capture, không chốt lúc place.
- Phí tạo journal ADJUSTMENT riêng: source LIABILITY/DEBIT → `VND_TRANSFER_FEE_INCOME` INCOME/CREDIT.
  Journal principal vẫn có đúng hai postings để giữ nguyên contract reversal. Cash-in/reversal không bị thu phí này.

## Transaction và database

Giữ nguyên claim → ordered bank pair → ordered ledger locks. Principal, fee journal, immutable assessment,
daily usage và transfer receipt/hold state nằm trong cùng READ COMMITTED transaction.
Account lock tuần tự hóa mọi outbound writer của nguồn; kiểm tra tổng usage sau lock, không có counter trong RAM.
Thiếu fee balance, vượt hạn mức hoặc lỗi receipt/assessment đều rollback toàn bộ và không giữ key thất bại.

V9 chỉ thêm chart metadata, policy và assessment; không tạo tiền hoặc sửa journal/receipt cũ.
SQL guard kiểm tra policy snapshot, business date, exact principal, exact balanced fee và gross limits.
Configured INTERNAL_TRANSFER không được commit khi thiếu assessment, kể cả bypass Java.
Assessment không update/delete/truncate. Timezone/identity của policy không đổi sau insert để tránh chia lại bucket;
policy không xóa/truncate. Bổ sung policy sau các giao dịch mặc định phải giữ timezone đã được dùng.
Policy row được giữ SHARE lock trong khi đánh giá; số tiền policy hợp lệ là số nguyên VND không âm tối đa 20 chữ số.

Giới hạn: journal SQL nội bộ trên account **chưa cấu hình policy** không bắt buộc assessment để giữ contract
trusted internal postings/migration fixtures cũ. Các public transfer/capture từ V9 luôn ghi assessment, kể cả fee 0.
Không có hạn mức customer tổng hợp, rolling window, FX, percent fee, min/max fee hay fee refund workflow.

## API và lỗi

Transfer/capture giữ response cũ. Đọc chi tiết bằng:

`GET /api/v1/ledger/accounts/{sourceAccountId}/outgoing-charges/{journalId}`

CUSTOMER phải sở hữu account nguồn; 200 + no-store gồm `journalId`, `amount`, `feeAmount`, `totalDebit`
(tất cả số tiền là chuỗi), `businessDate`, `timezone`, `feeJournalId` (null nếu fee 0).
Unknown/ngoài ownership: 404 `ACCOUNT_NOT_FOUND` hoặc `TRANSFER_CHARGE_NOT_FOUND`; OPERATOR-only: 403.
Journal trước V9 có thể không có assessment và trả 404; không suy diễn phí lịch sử bằng policy hiện tại.
Usage chỉ tính các assessment từ V9; migration không backfill giao dịch trước V9 vào hạn mức ngày.

Transfer/capture trả 422 `TRANSFER_TRANSACTION_LIMIT` hoặc `TRANSFER_DAILY_LIMIT` nếu gross vượt mức.
Thiếu tiền phí: 422 `LEDGER_INSUFFICIENT_FUNDS`. JWT, ownership, account status và idempotency contract vẫn áp dụng.

## Cấu hình demo

Sau khi chủ động migrate/chạy app local tới V9, dùng UUID nguồn synthetic của bạn:

```sql
INSERT INTO banking.outgoing_policies(account_id,transaction_limit,daily_limit,fee,timezone)
VALUES ('<source UUID>',1000000,5000000,1000,'Asia/Ho_Chi_Minh');
```

Transfer principal 100000 sẽ debit nguồn 101000, credit đích 100000 và credit income 1000.
Không tự áp policy lên `bank_lab` trong quá trình triển khai/tests. Các tests dùng PostgreSQL Testcontainers riêng.

## Acceptance

LedgerIT kiểm tra exact gross/fee balance và privacy, same-key replay sau policy change, contention daily limit,
fee/assessment rollback rồi retry cùng key, capture với fee/limits, SQL bypass/immutability, timezone local midnight,
usage ngày trước không tiêu hạn mức ngày nay và reversal giữ fee/usage.
