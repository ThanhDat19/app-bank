# BL-011a — Holds và số dư khả dụng

Phạm vi triển khai đầu tiên của BL-011: giữ VND, full capture đến một tài khoản nội bộ cố định,
release và expiry. [BL-011b](outgoing-policy.md) bổ sung hạn mức theo ngày/timezone và phí tại capture.

## API và quyền

CUSTOMER dùng JWT đã xác minh; issuer/subject xác định chủ hold. OPERATOR không thao tác thay.
Các response dùng `Cache-Control: no-store`; amount và ba loại balance là chuỗi số nguyên VND.

| Method/path | Request | Kết quả |
| --- | --- | --- |
| POST `/api/v1/holds` | Header `Idempotency-Key`; body `accountId`, `destinationAccountId`, `amount`, `expiresAt` | 201 + Location; ACTIVE hoặc trạng thái hiện tại khi replay |
| GET `/api/v1/holds/{id}` | Không body | 200; hold của actor khác/không tồn tại trả 404 |
| POST `/api/v1/holds/{id}/release` | Không body | 200; release lại trả cùng kết quả |
| POST `/api/v1/holds/{id}/capture` | Không body | 200; capture lại trả cùng journal |

```json
{"accountId":"<source UUID>","destinationAccountId":"<destination UUID>","amount":"60000","expiresAt":"2026-12-01T00:00:00Z"}
```

Expiry là UTC instant, được chuẩn hóa về microseconds của PostgreSQL. Chọn thời điểm tương lai khi demo.
Hai accounts phải khác nhau và ACTIVE khi place. Source đã provision và thuộc CUSTOMER; destination phải tồn tại.
Capture kiểm tra lại cả pair ACTIVE/provisioned. Destination được chốt lúc place; không nhận amount hay
destination mới khi capture. Không partial capture, gia hạn hay reversal của capture.
Capture áp dụng gross limits/phí của BL-011b; cần available cho phí bên ngoài principal đã giữ.

## Rules và transaction

- H01: `postedBalance` từ POSTED ledger; `reservedBalance` là tổng ACTIVE chưa hết hạn;
  `availableBalance = postedBalance - reservedBalance`. Đọc ba giá trị trong một SQL snapshot.
- H02: Place không tạo journal hay tiền. Amount chuỗi nguyên dương tối đa 20 chữ số; số JSON bị từ chối 400.
  Place chỉ thành công khi đủ available sau khi khóa account; lỗi rollback cả claim/key.
- H03: Key duy nhất theo issuer/subject. Cùng key/các field đã chuẩn hóa trả hold cũ kể cả sau release/capture/expiry;
  payload hợp lệ khác trả `409 IDEMPOTENCY_CONFLICT`. Key được giữ vô thời hạn.
- H04: `PENDING → ACTIVE → CAPTURED | RELEASED | EXPIRED`. PENDING không được commit.
  ACTIVE có expiry đã qua được trình bày EXPIRED ngay khi đọc và không giảm available, không cần scheduler.
  Release ghi terminal EXPIRED nếu đã hết hạn. Đọc không ghi DB.
- H05: Capture khóa hold, rồi toàn pair bank accounts theo UUID order, kiểm tra expiry sau khi chờ lock;
  bỏ reservation và ghi journal INTERNAL_TRANSFER với referenceId=holdId trong cùng READ COMMITTED transaction.
  Journal/postings phải khớp chính xác source/destination/amount/actor khi commit. Lỗi rollback giữ reservation cũ.
- H06: Release khóa hold rồi source; cho phép release trên FROZEN để tránh giữ tiền vô hạn.
  Capture sau release/expiry bị từ chối; release sau capture trả 409. Capture đã thành công luôn replay journal cũ.
- H07: Mọi posting (transfer, reversal, internal posting và SQL trực tiếp) phải giữ balance sau posting >= reserved.
  Account closure có SQL guard cho holds; posted balance khác 0 vẫn bị chặn bởi guard hiện tại.

Claim khóa hold trước account; place/capture khóa pair theo UUID, capture khóa ledger sau bank pair.
Account FK là DEFERRABLE INITIALLY DEFERRED để không lấy KEY SHARE lúc claim, tránh deadlock
giữa các hold ngược chiều trước ordered locking. Deferred guard kiểm tra ownership/destination.
V8 không sửa V1–V7, không seed tiền, không thay receipts/journals cũ. Metadata hold bất biến; terminal state,
delete và truncate bị chặn. `created_at` và captured journal giữ dấu vết; chưa có audit event riêng cho release.

## Errors và recovery

401/403 theo JWT/role; 404 `ACCOUNT_NOT_FOUND`/`HOLD_NOT_FOUND` không lộ ownership.
409 `ACCOUNT_STATE_INVALID`, `HOLD_STATE_INVALID`, `HOLD_EXPIRED`, `IDEMPOTENCY_CONFLICT`.
422 `HOLD_REQUEST_INVALID`, `HOLD_EXPIRY_INVALID`, `IDEMPOTENCY_KEY_INVALID`,
`LEDGER_NOT_PROVISIONED`, `LEDGER_LINK_INCONSISTENT`, `LEDGER_INSUFFICIENT_FUNDS`, `LEDGER_BALANCE_LIMIT`.
Bean validation/header/JSON sai trả 400 theo error contract chung.

Mất response sau place: retry nguyên key/payload; sau capture/release: retry cùng holdId/action hoặc GET.
Không tự tạo key mới để khắc phục timeout. Expiry dùng clock DB, không phụ thuộc clock browser;
capture phải còn hiệu lực khi cập nhật state. Không có cam kết thời gian capture khi expiry quá gần.

## Kiểm chứng

Các tests holds trong `LedgerIT` chạy HTTP JWT RSA/JWKS thật với PostgreSQL Testcontainers:
reserve/replay/conflict/release, contention không overbook, concurrent capture once-only,
receipt failure rollback/retry, expiry, roles/ownership/frozen release, SQL bypass và request validation.
Migration tests cash-in/transfer/reversal nâng tới V8 tiếp tục kiểm tra tiền/receipts cũ.
Không dùng H2 hoặc mock để thay bằng chứng lưu trữ tài chính.
