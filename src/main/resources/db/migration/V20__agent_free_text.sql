-- ②-A 自然语言 agent：任务目标改为自由文本（一句指令），不再是枚举
ALTER TABLE agent_task ALTER COLUMN goal TYPE TEXT;
