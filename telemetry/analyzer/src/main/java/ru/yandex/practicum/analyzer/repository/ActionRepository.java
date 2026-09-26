package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.yandex.practicum.analyzer.model.Action;

import java.util.Collection;

public interface ActionRepository extends JpaRepository<Action, Long> {

    @Modifying
    @Query("""
            delete from Action a where a.id in :ids
            and not exists (select sa from ScenarioAction sa where sa.action = a)
            """)
    void deleteUnreferencedByIds(@Param("ids") Collection<Long> ids);
}
