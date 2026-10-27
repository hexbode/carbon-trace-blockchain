package com.carbon.trace.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.cbam.CbamOutboxRelay;
import com.carbon.trace.cbam.CbamOutboxService;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.CbamOutboxEvent;
import com.carbon.trace.entity.CbamRegulatorInbox;
import com.carbon.trace.mapper.CbamRegulatorInboxMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CBAM Outbox 运维接口（仅 regulator / admin）。
 *
 *   POST /api/cbam/outbox/relay      手动触发一批中继投递
 *   GET  /api/cbam/outbox            查看 Outbox 事件（按状态筛选）
 *   GET  /api/cbam/regulator-inbox   查看监管消费收件箱（验证幂等落库）
 */
@RestController
@RequestMapping("/api/cbam")
public class CbamOutboxController {

    private final CbamOutboxService outboxService;
    private final CbamOutboxRelay outboxRelay;
    private final CbamRegulatorInboxMapper inboxMapper;

    public CbamOutboxController(CbamOutboxService outboxService,
                                CbamOutboxRelay outboxRelay,
                                CbamRegulatorInboxMapper inboxMapper) {
        this.outboxService = outboxService;
        this.outboxRelay = outboxRelay;
        this.inboxMapper = inboxMapper;
    }

    @PostMapping("/outbox/relay")
    public Map<String, Object> relay() {
        requireManagerRole();
        return outboxRelay.relayNow();
    }

    @GetMapping("/outbox")
    public List<CbamOutboxEvent> listOutbox(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer limit) {
        requireManagerRole();
        return outboxService.listEvents(status, limit);
    }

    @GetMapping("/regulator-inbox")
    public List<CbamRegulatorInbox> listInbox(@RequestParam(required = false) Integer limit) {
        requireManagerRole();
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        return inboxMapper.selectList(new LambdaQueryWrapper<CbamRegulatorInbox>()
                .orderByDesc(CbamRegulatorInbox::getId)
                .last("LIMIT " + size));
    }

    private void requireManagerRole() {
        LoginUser user = UserContext.get();
        String role = user == null ? null : user.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行 CBAM Outbox 运维操作");
        }
    }
}
