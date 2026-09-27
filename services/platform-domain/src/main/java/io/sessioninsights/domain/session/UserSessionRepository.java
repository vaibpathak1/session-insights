package io.sessioninsights.domain.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    List<UserSession> findBySiteIdAndAnonymousId(UUID siteId, String anonymousId);

    /**
     * identify(): attaches every session of an anonymous visitor to the identified end user.
     * Bulk update, so it bumps {@code version} itself to keep optimistic locking honest.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserSession s
               set s.endUserId = :endUserId, s.version = s.version + 1
             where s.siteId = :siteId and s.anonymousId = :anonymousId
               and (s.endUserId is null or s.endUserId <> :endUserId)""")
    int linkToEndUser(@Param("siteId") UUID siteId, @Param("anonymousId") String anonymousId,
                      @Param("endUserId") UUID endUserId);
}
