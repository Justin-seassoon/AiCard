package com.aicard.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
    List<AgentTask> findByCustomerIdAndStoreIdOrderByIdDesc(Long customerId, Long storeId);
}
