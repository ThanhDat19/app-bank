# Thử Ledger foundation trên Swagger

Làm [Account lifecycle](local-account-lifecycle.md) để có Customer VERIFIED và **account ACTIVE**.
Nếu account demo cũ đã CLOSED, dùng user/profile tổng hợp khác; không reset status hoặc xóa history.

Chạy bản BL-006 (branch `feat/BL-006-ledger-foundation`, hoặc main sau merge). Nếu IDE đã có cấu hình
DB đúng, dùng nút Run; hoặc trong terminal:

```powershell
. .\scripts\Use-LocalEnvironment.ps1
.\mvnw.cmd clean spring-boot:run
```

Flyway áp dụng V4. Migration giữ nguyên Customer/Account, chỉ seed clearing metadata, không tiền.
Nếu DB cũ có ledger_provisioned=true nhưng không có Ledger do từng sửa tay, migration từ chối:
cần điều tra cấu hình/dữ liệu, không xóa volume hoặc reset marker để ép chạy.

## CUSTOMER provision và xem balance

Mở http://localhost:8080/swagger-ui.html, Authorize CUSTOMER. Trong **Account**, GET `/accounts/me`
và lấy UUID `id` của account ACTIVE. Trong nhóm **Ledger**:

1. GET `/ledger/accounts/{accountId}/balance` trước provision → 422 LEDGER_NOT_PROVISIONED là đúng.
2. POST `/ledger/accounts/{accountId}/provision`, không request body → 200, accountType LIABILITY,
   normalSide CREDIT, có ledgerAccountId. Đây là liên kết sổ cái, không phải nạp tiền.
3. POST provision lần nữa → 200 cùng ledgerAccountId, không tạo link trùng.
4. GET balance → 200 với `postedBalance: "0"`. Số dư là string để giữ chính xác khi lớn.

Không có nút/API gửi journal tùy ý hoặc nạp tiền ở phiên này. Các test tạo tiền mô phỏng chỉ nằm trong
PostgreSQL Testcontainers dùng tạm, không chạy seed money vào DB local của bạn. Cash-in có idempotency
sẽ được triển khai ở BL-007 sau khi bạn nghiệm thu Ledger.

## OPERATOR xem balance

Browser/phiên riêng Authorize OPERATOR khác user. Nhóm **Ledger Operations** →
GET `/operations/ledger/accounts/{accountId}/balance` → 200 cùng số dư. CUSTOMER gọi route operations
bị 403; CUSTOMER khác gọi balance/provision của account này bị 404.

## Kiểm tra close sau provisioning

Với account ACTIVE có balance "0", CUSTOMER POST `/accounts/{id}/close`, reason CUSTOMER_REQUEST và
expectedVersion hiện tại từ GET Account. Provision không tăng lifecycle version, nên account mới vẫn version 0.
Mong đợi CLOSED/version +1. Đây là khác biệt với BL-005: giờ có Ledger thật để xác nhận posted balance zero.

Sau close, GET balance vẫn được; provision lại bị 409 ACCOUNT_STATE_INVALID. FROZEN cũng không provision.
Thao tác close là terminal trong MVP; nếu muốn tiếp tục demo, giữ một account khác ACTIVE.

## Xem dữ liệu chỉ đọc

Refresh schema `banking` trong IntelliJ/DBeaver, kết nối đúng database `bank_lab` theo `.env`:

```sql
SELECT id, code, account_type, normal_side, currency, bank_account_id
FROM banking.ledger_accounts;

SELECT id, reference_id, kind, status, currency, posted_at
FROM banking.ledger_journals;

SELECT journal_id, ledger_account_id, side, amount, currency
FROM banking.ledger_postings;
```

Sau provision chỉ có thêm customer ledger account; journals/postings vẫn rỗng cho đến khi có nghiệp vụ ghi tiền.
Không UPDATE balance (không có cột này), không sửa/xóa journal/posting, không nhập actor/token vào SQL demo.
Xem [đặc tả](../api/ledger.md), [ADR](../architecture/ADR-004-ledger-posting.md) và [evidence](../reviews/BL-006.md).
