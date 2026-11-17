/* ============================================================
   suicoco — 内置数据（演示工作区 / 终端模拟输出）
   ============================================================ */

// 演示工作区（相当于打开的文件夹 suicoco-demo）
const DEMO_WORKSPACE = {
  name: "suicoco-demo",
  files: {
    "hello.c":
`#include <stdio.h>

/* suicoco 演示工作区 */
int main(void) {
    int nums[5] = {1, 2, 3, 4, 5};
    int sum = 0;
    for (int i = 0; i < 5; i++) {
        sum += nums[i];
    }
    printf("sum = %d\\n", sum);
    return 0;
}
`,
    "main.cpp":
`#include <iostream>
#include <string>
#include <vector>

namespace demo {

class Greeter {
public:
    explicit Greeter(std::string name) : name_(std::move(name)) {}
    void greet() const {
        std::cout << "Hello, " << name_ << "! from suicoco" << std::endl;
    }
private:
    std::string name_;
};

}  // namespace demo

int main() {
    std::vector<demo::Greeter> gs;
    gs.emplace_back("suicoco");
    for (const auto& g : gs) {
        g.greet();
    }
    return 0;
}
`,
    "app.py":
`import sys
from dataclasses import dataclass


@dataclass
class Task:
    title: str
    done: bool = False


def main() -> None:
    tasks = [Task("搭建 suicoco 插件引擎"), Task("编写语言插件", True)]
    for i, t in enumerate(tasks, 1):
        status = "x" if t.done else " "
        print(f"[{status}] {i}. {t.title}")
    print("python", sys.version.split()[0], "on suicoco")


if __name__ == "__main__":
    main()
`,
    "Hello.java":
`public class Hello {
    private static final String APP = "suicoco";

    public static void main(String[] args) {
        System.out.println("Hello from " + APP + "!");
        for (int i = 0; i < 3; i++) {
            System.out.println("  step " + i);
        }
    }
}
`,
    "README.md":
`# suicoco 演示工作区

- 打开 \`hello.c\` / \`main.cpp\` / \`app.py\` / \`Hello.java\` 查看语法高亮
- 在 **插件市场** 安装官方语言插件（或用 Ctrl+Shift+P 打开命令面板）
- 在 **环境安装** 中安装 Ubuntu 精简系统与语言环境
- 底部终端支持模拟命令，输入 help 查看
`
  }
};

// 终端模拟运行输出（演示用；桌面版由插件任务在真实语言环境中执行）
const DEMO_RUN_OUTPUT = {
  "hello.c": ["sum = 15"],
  "main.cpp": ["Hello, suicoco! from suicoco"],
  "app.py": ["[x] 1. 搭建 suicoco 插件引擎", "[ ] 2. 编写语言插件", "python 3.11.0 on suicoco"],
  "Hello.java": ["Hello from suicoco!", "  step 0", "  step 1", "  step 2"]
};
