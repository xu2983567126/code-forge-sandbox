package com.xly.codeforgesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {

    private List<String> inputList;

    private String code;

    private String language;

    /**
     * 需物化到沙箱工作目录的文件内容列表。
     *
     * <p>沙箱会把每个元素写成独立文件，并把其<b>绝对路径</b>作为 argv 顺序传入待执行程序。
     * 用于特判：argv=[标准答案文件路径, 用户输出文件路径]（stdin 仍为测试输入）。</p>
     */
    private List<String> fileArgs;

    /**
     * 输入的承载方式。
     *
     * <ul>
     *   <li>{@code "file"}：沙箱把每个用例写成工作目录下的输入文件，并把路径追加到 argv 末尾，
     *       标准输入送空 —— 核心代码模式的驱动程序按路径读输入，不依赖标准输入。</li>
     *   <li>未设置：沙箱把用例输入写进标准输入。抽查程序（SPJ checker）按此约定读测试输入，
     *       不能跟着切换。</li>
     * </ul>
     */
    private String inputMode;
}
