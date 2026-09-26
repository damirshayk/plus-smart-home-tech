package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.yandex.practicum.analyzer.model.Sensor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SensorRepository extends JpaRepository<Sensor, String> {

    Optional<Sensor> findByIdAndHubId(String id, String hubId);

    List<Sensor> findByIdInAndHubId(Collection<String> ids, String hubId);

    @Modifying
    @Query(value = "INSERT INTO sensors (id, hub_id) VALUES (:id, :hubId) ON CONFLICT (id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("hubId") String hubId);

    @Modifying
    @Query("delete from Sensor s where s.id = :id and s.hubId = :hubId")
    int deleteByIdAndHubId(@Param("id") String id, @Param("hubId") String hubId);
}
