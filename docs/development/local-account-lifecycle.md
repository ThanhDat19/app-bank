# Thử Account lifecycle trên Swagger

Cần hai user khác nhau trong realm `bank-lab`: CUSTOMER và OPERATOR của client `bank-api`.
Làm [Customer/KYC](local-customer-kyc.md) trước: CUSTOMER tạo profile, OPERATOR khác identity duyệt VERIFIED.

Sau checkout `feat/BL-005-account-lifecycle` (hoặc main sau merge), chạy:

```powershell
. .\scripts\Use-LocalEnvironment.ps1
docker compose --profile iam up -d --wait postgres keycloak
.\mvnw.cmd spring-boot:run
```

Flyway áp dụng V3 tự động; không tạo bảng/seed/sửa status bằng tay. Refresh schema `banking` trong IntelliJ.
Nếu backend cũ còn chạy, dừng đúng process trước khi chạy bản mới.

## CUSTOMER mở tài khoản

Mở http://localhost:8080/swagger-ui.html → Authorize bằng CUSTOMER. Nếu đã Authorized đúng user thì
chỉ đóng dialog. Nhóm **Account → POST /api/v1/accounts → Try it out**:

```json
{"currency":"VND"}
```

Execute, xem **Server response**: 201, số account 12 chữ số, ACTIVE/version 0. Lưu **id UUID**;
các path `{id}` dùng UUID này, không dùng accountNumber. X-Correlation-ID có thể để trống.
GET `/accounts/me` trả array chứa account. Open lại nhận 409 ACCOUNT_ALREADY_EXISTS là đúng.
Nếu 422 KYC_NOT_VERIFIED, hoàn thành KYC bằng operator; đăng nhập không đồng nghĩa đã KYC.

## OPERATOR freeze/unfreeze

Dùng browser/cửa sổ riêng tư khác, Authorize OPERATOR khác owner. Trong **Account Operations**:

1. GET `/operations/accounts/{id}`, đọc state/version.
2. POST `/{id}/freeze`, body:

```json
{"expectedVersion":0,"reason":"SECURITY_REVIEW"}
```

Mong đợi 200 FROZEN/version 1. CUSTOMER GET account cũng thấy FROZEN.
CUSTOMER thử close lúc này bị 409 ACCOUNT_STATE_INVALID.

3. OPERATOR POST `/{id}/unfreeze`:

```json
{"expectedVersion":1,"reason":"REVIEW_CLEARED"}
```

Mong đợi 200 ACTIVE/version 2. Operator không được tự đổi state tài khoản của chính mình ngay cả khi có cả hai role.

## CUSTOMER đóng tài khoản chưa liên kết sổ cái

Phiên CUSTOMER, POST `/accounts/{id}/close`:

```json
{"expectedVersion":2,"reason":"CUSTOMER_REQUEST"}
```

Mong đợi 200 CLOSED/version 3. Nếu đóng ngay account mới chưa freeze/unfreeze, expectedVersion là 0.
Luôn dùng version vừa đọc, không copy số 2 cho mọi account.

BL-005 chưa có Ledger hoặc số dư. Chỉ account chưa provision Ledger được đóng. Nếu gặp 422
ACCOUNT_CLOSURE_UNAVAILABLE thì hệ thống thiếu authoritative financial checks; **không sửa marker trong DB
để bỏ qua**. Đợi phiên Ledger bổ sung adapter. CLOSED không mở lại, không tạo thêm account VND cùng profile.
Để demo lại từ đầu hãy dùng user/profile tổng hợp khác, không xóa history hoặc reset status bằng SQL.

## Các case review

| Thử | Mong đợi |
| --- | --- |
| User chưa có profile mở account | 404 CUSTOMER_NOT_FOUND |
| PENDING/REJECTED mở account | 422 KYC_NOT_VERIFIED |
| Tạo trùng | 409 ACCOUNT_ALREADY_EXISTS |
| CUSTOMER B xem/close account A | 404 ACCOUNT_NOT_FOUND |
| CUSTOMER gọi freeze | 403 |
| OPERATOR-only gọi open/close | 403 |
| Dual-role operator freeze account của mình | 422 ACCOUNT_SELF_OPERATION_FORBIDDEN |
| Dùng expectedVersion cũ | 409 ACCOUNT_VERSION_CONFLICT |
| Đổi reason giữa freeze/unfreeze | 422 ACCOUNT_REASON_INVALID |
| Đóng FROZEN hoặc đổi state CLOSED | 409 ACCOUNT_STATE_INVALID |

Chỉ đọc để kiểm chứng dữ liệu:

```sql
SELECT id, customer_id, account_number, currency, status, version FROM banking.accounts;
SELECT account_id, from_status, to_status, reason, account_version, changed_at
FROM banking.account_state_changes ORDER BY changed_at;
```

Một vòng open/freeze/unfreeze/close có history versions 0,1,2,3. Không có bảng/field balance trong BL-005.
Không gửi token/password hoặc identity links trong log/ảnh review. Xem [đặc tả](../api/account-lifecycle.md)
và [bằng chứng kiểm thử](../reviews/BL-005.md).
