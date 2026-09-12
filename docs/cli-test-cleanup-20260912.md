# 2026-09-12 旧测试产物清理

> 后续状态：用户随后授权清空全部本地生成数据，本文当时保留的应用/运行数据现也已移除；Git中的验证结果保留。参见[数据重置记录](cli-runtime-reset-20260912.md)。

按用户明确要求，完成CLI.0.8.12的新构建、实机浮字回归和实际应用包验收后，删除预先清点的旧测试目录与旧应用。执行前确认没有对应活跃进程、候选目录不含Git已跟踪文件、不覆盖正式playthroughs，并确认新的runtime-classpath不引用删除对象。删除清单在新测试开始前建立，新创建的结果不会混入其中。

- 删除1420个旧目录或文件，其中包括14份旧.app。
- 删除对象原分配空间约19.54 GiB；desktop-control/build由22.57 GiB降至3.16 GiB。该数值是目录分配空间，APFS共享块/快照可能使实际空闲空间变化不同。
- 清理范围包括旧fixtures及冻结runtime、旧package-check、fixture-runtime、runtime-images、smoke、旧语料/诊断输出、旧GUI包和旧playthrough运行包，以及旧构建日志/临时汇总。
- 整个desktop-control/build/playthroughs保留，尤其是暂停战士的20260909-030027-warrior-7df641a1；没有恢复正式游玩或修改其存档。用户Library/Application Support数据未列入候选。
- 当前0.8.12源应用、本轮中文路径应用副本、两个包测试profile、浮字fixture/profile与新的运行时指针全部保留；以后运行使用当前包，无需旧运行包。

所有已提交测试报告和脚本保留。旧报告中的build/路径现在是历史引用，不能再声称可从已删除目录读取原请求、异常或重建旧快照；可复查的是Git中的当时报告，并可用脚本重新生成测试证据。本次是用户授权的开发产物清理，不改变正式CLI对新产生审计的默认保留策略。

逐项路径、删除前体积与本轮保留位置见 [清理记录](cli-test-cleanup-20260912.json)。
