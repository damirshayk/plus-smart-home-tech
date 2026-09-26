package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.analyzer.model.Scenario;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    Optional<Scenario> findByHubIdAndName(String hubId, String name);

    // Чтобы не было N+1 запроса, но при этом не было дублирования сценариев в списке
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    default List<Scenario> findByHubId(String hubId) {
        List<Scenario> scenarios = findWithConditionsByHubId(hubId);
        if (!scenarios.isEmpty()) {
            findWithActionsByHubId(hubId);
        }
        return scenarios;
    }

    @Query("""
            select distinct s
            from Scenario s
            left join fetch s.conditions sc
            left join fetch sc.sensor
            left join fetch sc.condition
            where s.hubId = :hubId
            order by s.id
            """)
    List<Scenario> findWithConditionsByHubId(@Param("hubId") String hubId);

    @Query("""
            select distinct s
            from Scenario s
            left join fetch s.actions sa
            left join fetch sa.sensor
            left join fetch sa.action
            where s.hubId = :hubId
            order by s.id
            """)
    List<Scenario> findWithActionsByHubId(@Param("hubId") String hubId);
}
