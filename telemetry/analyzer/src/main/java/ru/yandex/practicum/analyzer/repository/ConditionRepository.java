package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.yandex.practicum.analyzer.model.Condition;

import java.util.Collection;

public interface ConditionRepository extends JpaRepository<Condition, Long> {

    @Modifying
    @Query("""
            delete from Condition c where c.id in :ids
            and not exists (select sc from ScenarioCondition sc where sc.condition = c)
            """)
    void deleteUnreferencedByIds(@Param("ids") Collection<Long> ids);
}
