package com.acme.kb.p1.wiki;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.config.KbProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Wiki 输入的入口校验。分类严格按 `p1/contracts/error-codes.md`：
 * 标题问题走 `WIKI_TITLE_INVALID`（永久）、正文超限走 `WIKI_BODY_TOO_LARGE`（永久），
 * slug 与审核意见问题走 `VALIDATION_FAILED`——不新增码，避免"声明不可产生"。
 */
@Component
public class WikiValidator {

    public static final int TITLE_MAX_CHARS = 200;
    public static final int SUMMARY_MAX_CHARS = 500;
    public static final int DECISION_NOTE_MAX_CHARS = 2000;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9\\-]{1,79}$");

    private final int maxBodyBytes;

    public WikiValidator(KbProperties props) {
        this.maxBodyBytes = props.wiki().maxBodyBytes();
    }

    public void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ApiException(ErrorCode.WIKI_TITLE_INVALID, "标题不能为空");
        }
        if (title.length() > TITLE_MAX_CHARS) {
            throw new ApiException(ErrorCode.WIKI_TITLE_INVALID, "标题超过 " + TITLE_MAX_CHARS + " 字");
        }
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                throw new ApiException(ErrorCode.WIKI_TITLE_INVALID, "标题含控制字符");
            }
        }
    }

    public void requireSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "slug 需为小写字母、数字与连字符，长度 2–80");
        }
    }

    /** 正文允许为空（建页时只给标题与 slug 是合法用例），但不允许超限。 */
    public void requireBody(String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBodyBytes) {
            throw new ApiException(ErrorCode.WIKI_BODY_TOO_LARGE,
                    "正文 " + bytes + " 字节，超过上限 " + maxBodyBytes + "，请拆分页面");
        }
    }

    public void requireSummary(String summary) {
        if (summary != null && summary.length() > SUMMARY_MAX_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "摘要超过 " + SUMMARY_MAX_CHARS + " 字");
        }
    }

    /** reject 必须给理由（E2/R3 前置校验）；理由原文不落库，只落摘要哈希。 */
    public void requireDecisionNote(String note) {
        if (note == null || note.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "退回必须填写理由");
        }
        if (note.length() > DECISION_NOTE_MAX_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "退回理由超过 " + DECISION_NOTE_MAX_CHARS + " 字");
        }
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }
}
