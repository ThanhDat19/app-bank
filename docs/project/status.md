# Tình trạng dự án — 02/11/2026

## Đối chiếu tiến độ

GitHub đã xác nhận [PR #21](https://github.com/ThanhDat19/app-bank/pull/21) (BL-008) và
[PR #23](https://github.com/ThanhDat19/app-bank/pull/23) (BL-009/010) được owner merge ngày 02/11/2026.
Main hiện ở `1c68c4b`; local main đã fast-forward. Các phiếu review cũ ghi “chờ merge” là trạng thái lịch sử.

| Phần | Tình trạng |
| --- | --- |
| BL-001–003 | Bootstrap, API conventions/module boundaries, IAM đã có trên main |
| BL-004–006 | Customer/KYC, account lifecycle, immutable double-entry ledger đã có trên main |
| BL-007–010 | Idempotent cash-in/transfer, concurrency/recovery, full reversal đã merge |
| BL-011a | Holds/available balance đã triển khai local trên `feat/BL-011-holds` |
| BL-011b | Hạn mức transaction/ngày theo timezone và phí nguyên tử đã triển khai local; full verify đạt, chờ review |
| BL-012 trở đi | Outbox/Kafka/reconciliation/history/external payments/operations theo roadmap |

Roadmap là thứ tự dependency, lịch sprint ban đầu chưa được re-estimate. Số lượng tác vụ có mã nguồn
không đồng nghĩa chứng nhận vận hành. Cloud/Analytics addendum vẫn là draft, không thay roadmap chính.

## Bằng chứng baseline

- PR #23 ghi full CI final head `ad880d3` đạt, với baseline 268 tests ở run implementation trước đó.
- Local trước thay đổi: Java 21, Maven offline, **78 unit/web/architecture tests PASS**, không skip.
- Docker Desktop có thể dùng qua quyền chạy ngoài sandbox; PostgreSQL integration chạy bằng Testcontainers riêng.
- Workspace ban đầu sạch. Không thay `.env`, dữ liệu `bank_lab`, quyền GitHub hay tự merge PR.

## Phạm vi thay đổi tiếp theo

[BL-011a](../api/holds.md) triển khai place/capture/release/expiry, số dư reserved/available,
V8 SQL guards và kiểm thử concurrency/rollback/security. Đây là phần holds có thể review riêng;
[BL-011b](../api/outgoing-policy.md) bổ sung gross transaction/daily limits, immutable policy snapshot,
fee income postings và API đọc assessment. BL-011 chờ owner review policy/nghiệm thu; chưa merge/Done.

## Kết quả kiểm chứng BL-011a

Local `clean verify` ngày 02/11/2026, Java 21/PostgreSQL 16.14 Testcontainers:
**278/278 PASS** — 78 unit/web/architecture + 200 integration; 0 failures/errors/skipped,
BUILD SUCCESS trong 2:54. Có 10 ca holds mới trong 42 tests của LedgerIT; baseline 268 cases tiếp tục đạt.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=D:\App\app-bank\.m2-local' '-Dspring.test.context.cache.maxSize=1' clean verify
```

Log local: `.tools/BL-011-final-verify.log` (ignored). PackagedApplicationIT xác nhận V8 nằm trong JAR;
BootstrapIT kiểm tra Flyway V8/OpenAPI/HTTP. Chưa chạy JAR bằng process độc lập hoặc thao tác Swagger bằng browser.
Các ca mới bao gồm same-key/opposite-direction contention, capture-vs-release race, once-only capture,
reserve/release/expiry, rollback/retry, SQL guards và JWT ownership/roles.

`git diff --check` đạt. Thay đổi hiện nằm local, chưa commit/push/mở PR; chưa có CI cho nhánh mới.
Không tự gán BL-011 Done hoặc merge thay owner.

## Kết quả kiểm chứng BL-011a+b

Source hiện tại `clean verify` ngày 02/11/2026: **286/286 PASS** — 78 unit/web/architecture +
208 PostgreSQL integration, 0 failures/errors/skipped, BUILD SUCCESS 4:50.
Log `.tools/BL-011-complete-verify.log` (ignored). LedgerIT có 50 tests, gồm 10 holds và 8 policy/fee cases mới;
baseline 268 cases tiếp tục đạt. Lệnh tái lập giống trên; Flyway hiện tới V9 và JAR chứa cả V8/V9.

Kiểm tra mới: exact fee/gross snapshots và ownership, replay không thu lại phí sau đổi policy,
8 competing debits không vượt daily gross cap, fee/assessment faults rollback và retry cùng key,
hold capture cần available cho phí, SQL policy bypass/immutability, local-midnight boundary,
usage ngày trước không tiêu hạn mức hôm nay, reversal giữ nguyên fee/usage.

Các lựa chọn cần owner review: gross limits theo source account, timezone cố định, flat fee mô phỏng,
principal reversal không hoàn phí/khôi phục usage; usage chỉ từ assessments V9, không backfill trước V9.
Mặc định fee 0 và trần kỹ thuật 20 chữ số; chưa có API quản trị policy.

Code và docs nằm local trên `feat/BL-011-holds`, chưa commit/push/CI/PR. Không migrate hoặc ghi dữ liệu vào
`bank_lab` local; chỉ PostgreSQL Testcontainers tạm. Bước tiếp theo theo roadmap là BL-012 transactional outbox,
sau khi owner review/nghiệm thu BL-011.
