package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.yandex.practicum.analyzer.model.Sensor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SensorRepository extends JpaRepository<Sensor, String> {

    Optional<Sensor> findByIdAndHubId(String id, String hubId);

    List<Sensor> findByIdInAndHubId(Collection<String> ids, String hubId);
}
