# ADR-004 — Ledger nguyên tử và số dư từ bút toán đã ghi

- Ngày: 26/10/2026. Trạng thái: đề xuất BL-006, owner review trong PR.
- Issue [#16](https://github.com/ThanhDat19/app-bank/issues/16).

## Quyết định

Một core/PostgreSQL transaction giữ journal, postings và trạng thái tài khoản liên quan. Không RPC/Kafka
trong khóa DB. Ledger dùng VND nguyên, BigDecimal và NUMERIC chính xác; HTTP số dư trả decimal string
để tránh mất độ chính xác ở client JavaScript khi vượt giới hạn integer an toàn của Number.

Chart tối thiểu: VND_CASH_CLEARING là ASSET/DEBIT; customer deposit là LIABILITY/CREDIT. Số dư normal-side
bằng sum(postings cùng normal side) trừ sum(side còn lại), chỉ lấy journals POSTED. Không có cached balance
ở phiên này; không UPDATE balance hoặc seed tiền mở tài khoản. Clearing là đối ứng cash-in mô phỏng,
không phải chứng từ đã nhận tiền thật từ mạng thanh toán.

Journal gồm 2–100 lines, ít nhất hai ledger accounts, amount nguyên dương <=20 chữ số, currency khớp và
tổng debit = credit. DRAFT chỉ là trạng thái tạm trong transaction; constraint trigger deferred không cho
DRAFT tồn tại sau commit. Finalization kiểm tra và khóa, sau POSTED journal/lines append-only.
REVERSAL trong kind chưa phải nghiệp vụ đảo; BL-010 phải thêm liên kết journal gốc và chính sách đảo.

## Vì sao dùng cả Java và DB

Java trả business failure rõ ràng trước khi ghi, nhưng code path hoặc thao tác SQL khác có thể bỏ qua
service. PostgreSQL finalization trigger kiểm tra balance, trạng thái account và không âm số dư customer;
triggers chặn sửa/xóa/append vào journal đã ghi. Composite FKs giữ journal/ledger/posting cùng currency.
Ràng buộc NUMERIC không dùng scale 0 vì PostgreSQL có thể làm tròn input trước CHECK. Unconstrained
NUMERIC + CHECK integer/positive/max giữ nguyên raw input để từ chối phần lẻ, NaN và Infinity.

## Locks và isolation

Một posting command khóa bank account IDs theo ORDER BY UUID ở PostgreSQL, rồi ledger IDs theo cùng
thứ tự DB. Không dùng Java UUID.compareTo để thay thứ tự SQL. Ledger identity immutable nên dùng
FOR NO KEY UPDATE: tuần tự hóa các writer, tương thích KEY SHARE mà foreign key postings giữ.
FOR UPDATE ở ledger từng gây deadlock trong test SQL ghi nợ concurrent; đã thay cả Java adapter và trigger.
Bank account row vẫn FOR UPDATE để phối hợp với close/freeze/provision.

READ COMMITTED là isolation được hỗ trợ cho writes/financial close ở BL-006. DB từ chối finalization
ở isolation khác; không suy diễn MVCC snapshot cũ là balance hiện tại. Test có cả service path và direct SQL
hai debit cạnh tranh. Một command ghi một journal; fee của một giao dịch nên là các lines của cùng journal.
Nếu tương lai cần nhiều journals trong một outer transaction, phải pre-lock toàn bộ tập account theo một
thứ tự trước journal đầu tiên hoặc bổ sung coordinator; không nối tùy ý nhiều calls với tập khóa khác nhau.
Không tuyên bố loại bỏ mọi deadlock do arbitrary SQL/DDL; DB có thể abort và caller phải rollback/retry
toàn command theo idempotency contract ở BL-007/008.

## Ranh giới module và API

Ledger gọi AccountFinancialAccess, không đọc Account domain trực tiếp. Account không phụ thuộc Ledger.
Composition root cấu hình AccountClosureGuard gọi LedgerQueryService để tránh module cycle.
Provisioning lock account ACTIVE, insert ledger link và set marker cùng transaction. DB deferred constraint
kiểm tra marker iff link; marker không được reset, ledger identity không được đổi. Close đọc balance dưới
cùng account lock, chỉ cho zero. DB cũng chặn raw close tài khoản có tiền.

Chỉ có REST provision/balance cho owner và balance operations. Không generic journal-write REST.
LedgerPostingService là primitive nội bộ, MANDATORY outer transaction và actor đã xác minh. Financial use
case tương lai chịu trách nhiệm authorization/ownership riêng, idempotency/request hash, limits và status;
unique journal reference chỉ chống ghi lại reference, không thay thế idempotency API hay quyền chuyển tiền.

## Tradeoffs và phần tiếp theo

Derived balance đơn giản để chứng minh đúng, nhưng chi phí tăng theo số postings; cần đo trước khi thêm
projection. Nếu thêm projection, update cùng transaction và reconcile với journal; không đổi nguồn sự thật.
Holds/pending payment chưa có schema/API nên BL-006 closure chỉ kiểm tra posted balance. BL-011/018 phải
nối các trạng thái này dưới cùng lock và thêm races trước khi bật features. Không tự trả available balance.

DB guards bảo vệ DML bình thường khi triggers bật; chủ DB/superuser vẫn có thể DDL/disable triggers.
DB roles least privilege, separation migration/runtime, audit chống sửa/retention/backup và hiệu năng
production chưa được chứng nhận. Đây là simulation với dữ liệu tổng hợp.

Nguồn: [PostgreSQL Numeric](https://www.postgresql.org/docs/16/datatype-numeric.html),
[row-level locks](https://www.postgresql.org/docs/16/explicit-locking.html),
[constraint triggers](https://www.postgresql.org/docs/16/sql-createtrigger.html).
