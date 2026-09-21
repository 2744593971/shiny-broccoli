package com.duli.security;

import com.duli.pojo.Vlog;
import com.duli.service.IVlogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class VideoAccess {
    @Autowired
    private IVlogService vlogService;

    public Vlog requireReadable(String vlogId, String viewerId) {
        Vlog vlog = vlogService.getById(vlogId);
        if (vlog == null || (!Integer.valueOf(0).equals(vlog.getIsPrivate())
                && (viewerId == null || !viewerId.equals(vlog.getVlogerId())))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "视频不存在或无权访问");
        }
        return vlog;
    }

    public void requireOwner(String vlogId, String userId) {
        Vlog vlog = requireReadable(vlogId, userId);
        if (!userId.equals(vlog.getVlogerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能修改自己的视频");
        }
    }
}
