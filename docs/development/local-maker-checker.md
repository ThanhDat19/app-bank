# BL-019 — Thử maker-checker trên Swagger

Build/run bản V16 theo local setup, chỉ dùng dữ liệu tổng hợp. Cần CUSTOMER và hai OPERATOR có identity khác nhau
trong client `bank-api`. Cả hai OPERATOR phải khác owner account khi freeze/unfreeze. Không tự thay đổi database bằng SQL.

1. CUSTOMER mở/provision account, dùng OPERATOR cash-in như trước. Lưu account UUID và current version từ GET.
2. Authorize OPERATOR A. Nhóm Maker-checker, POST `/api/v1/operations/requests` với key mới và:

   ```json
   {"type":"FREEZE","targetId":"<account UUID>","expectedAccountVersion":0,"reason":"SECURITY_REVIEW"}
   ```

3. 201 trả operation `id`, PENDING/version0. Account vẫn ACTIVE. Lưu Location/id/key.
4. A thử POST `/{id}/approve` body `{"expectedVersion":0}`:422 OPERATION_SELF_APPROVAL_FORBIDDEN.
5. Authorize OPERATOR B ở phiên khác, approve với body trên:200 APPROVED, `result.status=FROZEN`, account version+1.
6. B retry approve cùng id/version0: trả nguyên receipt; không thêm history/account version. A GET request/history được,
   CUSTOMER gọi queue/history/approve403. Xem `/operations/audit-events`: OPERATION_REQUESTED và OPERATION_APPROVED.
7. A tạo UNFREEZE với account version mới và REVIEW_CLEARED, B approve để ACTIVE trở lại.
8. Để hoàn một cash-in/transfer, A tạo REVERSAL với targetId là original journal UUID, không truyền amount/version account.
   B approve; `result` chứa full reversal receipt. Balances/holds/state checks vẫn có thể từ chối; PENDING giữ nguyên khi lỗi.
9. A tạo intent khác rồi cancel với `{"expectedVersion":0,"reason":"MAKER_WITHDREW"}`, hoặc B reject với NOT_JUSTIFIED.
   Các state này terminal. Cùng actor/action retry trả receipt, không đổi thành APPROVED.

Legacy POST freeze/unfreeze/reversal trả409 OPERATION_APPROVAL_REQUIRED. Existing GET reversal receipt vẫn hoạt động;
maker đọc receipt qua operation result, checker có thể GET reversal riêng theo id. Không gọi API cũ để replay sau timeout.
Giữ nguyên key cho create retry; giữ đúng checker/id cho approve retry. Nếu sửa target/reason/account version, hủy intent
cũ và dùng key mới. Không tạo quyền ghi SQL hoặc tắt guard để xử lý demo lỗi.

[API/errors/recovery](../api/maker-checker.md) · [ADR-013](../architecture/ADR-013-maker-checker.md).
