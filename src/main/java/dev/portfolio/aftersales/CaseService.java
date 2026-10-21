package dev.portfolio.aftersales;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
class CaseService {
    private static final Set<String> TYPES = Set.of("RETURN", "REFUND", "LOGISTICS");
    private final Store store;

    CaseService(Store store) { this.store = store; }

    CurrentUser current(String username) {
        UserRecord user = store.findUser(username).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        return new CurrentUser(user.id(), user.username(), user.role());
    }

    List<OrderRecord> orders(CurrentUser user) {
        return "AGENT".equals(user.role()) ? store.allOrders() : store.ordersFor(user.id());
    }

    OrderRecord order(long id, CurrentUser user) {
        OrderRecord order = store.order(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (!"AGENT".equals(user.role()) && order.customerId() != user.id())
            throw new ApiException(HttpStatus.FORBIDDEN, "无权查看该订单");
        return order;
    }

    List<CaseRecord> tickets(CurrentUser user) {
        return "AGENT".equals(user.role()) ? store.allTickets() : store.ticketsFor(user.id());
    }

    CaseDetail detail(long id, CurrentUser user) {
        CaseRecord ticket = accessible(id, user);
        return new CaseDetail(ticket, order(ticket.orderId(), user), store.events(id),
                store.analysis(id).orElse(null), store.task(id).orElse(null));
    }

    private CaseRecord accessible(long id, CurrentUser user) {
        CaseRecord ticket = store.ticket(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "售后单不存在"));
        if (!"AGENT".equals(user.role()) && ticket.customerId() != user.id())
            throw new ApiException(HttpStatus.FORBIDDEN, "无权查看该售后单");
        return ticket;
    }

    private void requireAgent(CurrentUser user) {
        if (!"AGENT".equals(user.role())) throw new ApiException(HttpStatus.FORBIDDEN, "仅客服可执行此操作");
    }

    @Transactional
    CaseDetail create(CreateCaseRequest request, CurrentUser user) {
        if (!"CUSTOMER".equals(user.role())) throw new ApiException(HttpStatus.FORBIDDEN, "仅顾客可提交售后申请");
        String type = request.caseType().trim().toUpperCase();
        if (!TYPES.contains(type)) throw new ApiException(HttpStatus.BAD_REQUEST, "售后类型无效");

        // Locking the order serializes case creation for this order, including two distinct idempotency keys.
        OrderRecord order = store.lockOrder(request.orderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (order.customerId() != user.id()) throw new ApiException(HttpStatus.FORBIDDEN, "不能申请他人的订单");
        CaseRecord existing = store.ticketByKey(user.id(), request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (existing.orderId() != order.id() || !existing.caseType().equals(type) || !existing.reason().equals(request.reason()))
                throw new ApiException(HttpStatus.CONFLICT, "幂等键已用于另一笔申请");
            return detail(existing.id(), user);
        }
        if (!"PAID".equals(order.orderStatus())) throw new ApiException(HttpStatus.CONFLICT, "此订单当前不能申请售后");
        if ("RETURN".equals(type) && (!"DELIVERED".equals(order.logisticsStatus())
                || order.deliveredAt() == null || order.deliveredAt().isBefore(LocalDateTime.now().minusDays(7))))
            throw new ApiException(HttpStatus.CONFLICT, "退货申请需在签收后7日内提交");
        if ("LOGISTICS".equals(type) && !"IN_TRANSIT".equals(order.logisticsStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "仅运输中的订单可提交物流异常");
        if (store.hasActiveCase(order.id())) throw new ApiException(HttpStatus.CONFLICT, "此订单已有处理中的售后单");

        long id = store.createTicket(order.id(), user.id(), type, request.reason(), request.idempotencyKey());
        store.event(id, user.id(), "CREATED", null, "SUBMITTED", request.reason());
        store.createTask(id);
        return detail(id, user);
    }

    @Transactional
    CaseDetail take(long id, CurrentUser user) {
        requireAgent(user);
        CaseRecord ticket = accessible(id, user);
        if (!"SUBMITTED".equals(ticket.status())) throw new ApiException(HttpStatus.CONFLICT, "售后单当前不能领取");
        if (!store.transition(id, "SUBMITTED", "IN_REVIEW", ticket.version(), user.id()))
            throw new ApiException(HttpStatus.CONFLICT, "售后单已被其他客服处理，请刷新");
        store.event(id, user.id(), "TAKEN", "SUBMITTED", "IN_REVIEW", "客服开始处理");
        return detail(id, user);
    }

    @Transactional
    CaseDetail decide(long id, DecisionRequest request, CurrentUser user) {
        requireAgent(user);
        CaseRecord ticket = accessible(id, user);
        if (!"IN_REVIEW".equals(ticket.status())) throw new ApiException(HttpStatus.CONFLICT, "请先领取售后单");
        if (ticket.assignedTo() == null || ticket.assignedTo() != user.id())
            throw new ApiException(HttpStatus.FORBIDDEN, "只能处理自己领取的售后单");
        String decision = request.decision().trim().toUpperCase();
        if (!Set.of("APPROVED", "REJECTED").contains(decision))
            throw new ApiException(HttpStatus.BAD_REQUEST, "处理决定无效");
        if (!store.transition(id, "IN_REVIEW", decision, request.expectedVersion(), user.id()))
            throw new ApiException(HttpStatus.CONFLICT, "售后单已更新，请刷新");
        store.event(id, user.id(), "DECIDED", "IN_REVIEW", decision, request.note());
        return detail(id, user);
    }

    @Transactional
    CaseDetail close(long id, CurrentUser user) {
        requireAgent(user);
        CaseRecord ticket = accessible(id, user);
        if (!Set.of("APPROVED", "REJECTED").contains(ticket.status()))
            throw new ApiException(HttpStatus.CONFLICT, "售后单当前不能关闭");
        if (!store.transition(id, ticket.status(), "CLOSED", ticket.version(), ticket.assignedTo()))
            throw new ApiException(HttpStatus.CONFLICT, "售后单已更新，请刷新");
        store.event(id, user.id(), "CLOSED", ticket.status(), "CLOSED", "处理结束");
        return detail(id, user);
    }

    CaseDetail retryAnalysis(long id, CurrentUser user) {
        requireAgent(user);
        CaseRecord ticket = accessible(id, user);
        AnalysisTask task = store.task(ticket.id()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "分析任务不存在"));
        if (!"FAILED".equals(task.status())) throw new ApiException(HttpStatus.CONFLICT, "只有失败的任务可以重试");
        store.retryTask(id);
        return detail(id, user);
    }
}
