# Tình trạng dự án — 03/11/2026

## Đối chiếu tiến độ

GitHub đã xác nhận [PR #21](https://github.com/ThanhDat19/app-bank/pull/21) (BL-008) và
[PR #23](https://github.com/ThanhDat19/app-bank/pull/23) (BL-009/010) được owner merge ngày 02/11/2026.
PR #24 (BL-011), #26 (BL-012) đã merge ngày 02/11/2026; #28 (BL-013) owner merge ngày 03/11/2026.
Main hiện ở `371aa0b`; local main đồng bộ trước khi tạo nhánh BL-014. Các ghi chú “chưa push/PR/merge” bên dưới là snapshot lịch sử.

| Phần | Tình trạng |
| --- | --- |
| BL-001–003 | Bootstrap, API conventions/module boundaries, IAM đã có trên main |
| BL-004–006 | Customer/KYC, account lifecycle, immutable double-entry ledger đã có trên main |
| BL-007–010 | Idempotent cash-in/transfer, concurrency/recovery, full reversal đã merge |
| BL-011 | Holds/available balance/limits/fees đã merge PR #24; local 286 tests và CI PASS |
| BL-012 | Issue #25 / PR #26 đã merge; local 299 tests và GitHub CI PASS |
| BL-013 | Issue #27 / PR #28 đã merge; local 313 tests và GitHub CI PASS |
| BL-014 | Issue #29, `feat/BL-014-reconciliation-audit` từ main 371aa0b; snapshot reports/audit và retry source receipts đã triển khai, local 331 tests PASS; chờ PR/CI và review |
| BL-015 trở đi | History/statement, SSE, external payments/operations theo roadmap |

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

## BL-012 — bản triển khai tiếp theo

BL-011 đã merge PR #24. BL-012 được chuẩn bị theo issue #25 và feature branch riêng từ main mới nhất.
V10 auto event nguyên tử cùng POSTED journal, immutable envelope + delivery leases, token fencing, retry/backoff;
default relay off và không fake transport. Local full clean verify **299/299 PASS** (80 unit + 219 integration),
0 failures/errors/skipped. [Review/evidence](../reviews/BL-012.md).
GitHub PR/CI là bước bàn giao tiếp theo; trạng thái live xem issue #25/PR liên kết.

## BL-013 — Kafka và notifications

BL-012 đã merge PR #26; task mới có issue #27 và feature branch từ main cập nhật.
Kafka producer ACK trước outbox completion; consumer inbox chống trùng, notification commit trước offset,
durable retry/backoff và sanitized Kafka DLQ/quarantine. CUSTOMER đọc bounded notifications theo ownership.
Kafka/relay mặc định tắt, không email/SMS, không thay đổi số dư từ event.
Local full clean verify **313/313 PASS** (82 unit/web/architecture + 231 integration), 0 failures/errors/skipped;
có Kafka/PostgreSQL thật, broker interruption/recovery, consumer restart, public transfer cho cả sender/recipient.
[Review/evidence](../reviews/BL-013.md). Bàn giao gồm code/tests và docs commits, push và Draft PR liên kết issue #27;
trạng thái live PR/CI được ghi trên GitHub. Không tự approve/merge hoặc migrate bank_lab local.

## BL-014 — Reconciliation & Audit

BL-013 đã merge PR #28. Issue #29 và feature branch riêng từ main cập nhật; không có review comments tồn đọng.
REPEATABLE READ reports phân biệt structural mismatches và eventual-delivery warnings; không tự sửa nguồn.
V12 audit actor/action/time cùng financial transaction và completed report; processed-source receipts tránh báo sai
retry đã phục hồi. Legacy journal/retry inventories immutable, không tự suy diễn lịch sử thiếu bằng chứng.
Local full clean verify **331/331 PASS** (83 unit/web/architecture + 248 integration), 0 failures/errors/skipped.
[Review/evidence](../reviews/BL-014.md). Hai commits code/tests và docs được bàn giao qua push + Draft PR liên kết issue #29;
trạng thái CI/review live nằm trên GitHub. Scheduler default off; bank_lab local chưa bị migrate hoặc repair.
