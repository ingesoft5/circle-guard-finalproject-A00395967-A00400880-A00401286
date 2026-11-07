package com.circleguard.promotion.service;

import com.circleguard.promotion.model.graph.CircleNode;
import com.circleguard.promotion.model.graph.UserNode;
import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircleServiceTest {

    @Mock
    private CircleNodeRepository circleRepository;
    @Mock
    private HealthStatusService healthStatusService;

    @InjectMocks
    private CircleService circleService;

    private static UserNode user(String id, String status) {
        return UserNode.builder().anonymousId(id).status(status).build();
    }

    private static CircleNode circleWith(UserNode... members) {
        return CircleNode.builder().id(1L).name("Study group")
                .members(new HashSet<>(Set.of(members))).build();
    }

    @Test
    void toggleCircleValidity_validCircle_invalidatesAndResolvesEveryMember() {
        CircleNode circle = circleWith(user("u1", "SUSPECT"), user("u2", "PROBABLE"));
        when(circleRepository.findById(1L)).thenReturn(Optional.of(circle));

        circleService.toggleCircleValidity(1L);

        assertThat(circle.getIsValid()).isFalse();
        verify(circleRepository).save(circle);
        verify(healthStatusService).resolveStatus("u1");
        verify(healthStatusService).resolveStatus("u2");
    }

    @Test
    void toggleCircleValidity_invalidCircle_revalidatesWithoutResolvingMembers() {
        CircleNode circle = circleWith(user("u1", "SUSPECT"));
        circle.setIsValid(false);
        when(circleRepository.findById(1L)).thenReturn(Optional.of(circle));

        circleService.toggleCircleValidity(1L);

        assertThat(circle.getIsValid()).isTrue();
        verify(circleRepository).save(circle);
        verifyNoInteractions(healthStatusService);
    }

    @Test
    void toggleCircleValidity_unknownCircle_throws() {
        when(circleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> circleService.toggleCircleValidity(99L))
                .hasMessage("Circle not found");
        verify(circleRepository, never()).save(any());
    }

    @Test
    void forceFenceCircle_promotesOnlyActiveMembersToProbable() {
        CircleNode circle = circleWith(user("active-1", "ACTIVE"), user("confirmed-1", "CONFIRMED"));
        when(circleRepository.findById(1L)).thenReturn(Optional.of(circle));

        circleService.forceFenceCircle(1L);

        assertThat(circle.getForceFence()).isTrue();
        verify(circleRepository).save(circle);
        verify(healthStatusService).updateStatus("active-1", "PROBABLE");
        verify(healthStatusService, never()).updateStatus(eq("confirmed-1"), anyString());
    }

    @Test
    void forceFenceCircle_unknownCircle_throws() {
        when(circleRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> circleService.forceFenceCircle(5L))
                .hasMessage("Circle not found");
        verifyNoInteractions(healthStatusService);
    }

    @Test
    void createCircle_generatesMeshInviteCodeAndPersistsActiveCircle() {
        when(circleRepository.existsByInviteCode(anyString())).thenReturn(false);
        when(circleRepository.save(any(CircleNode.class))).thenAnswer(inv -> inv.getArgument(0));

        CircleNode created = circleService.createCircle("Lab partners", "loc-1");

        assertThat(created.getName()).isEqualTo("Lab partners");
        assertThat(created.getLocationId()).isEqualTo("loc-1");
        assertThat(created.getIsActive()).isTrue();
        assertThat(created.getCreatedAt()).isNotNull();
        // Ambiguous characters (0, O, 1, I) are excluded from the alphabet
        assertThat(created.getInviteCode()).matches("MESH-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}");
    }

    @Test
    void createCircle_inviteCodeCollision_retriesUntilUnique() {
        when(circleRepository.existsByInviteCode(anyString())).thenReturn(true, true, false);
        when(circleRepository.save(any(CircleNode.class))).thenAnswer(inv -> inv.getArgument(0));

        CircleNode created = circleService.createCircle("Team", null);

        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(circleRepository, times(3)).existsByInviteCode(codes.capture());
        assertThat(created.getInviteCode()).isEqualTo(codes.getAllValues().get(2));
    }

    @Test
    void joinCircle_validCode_returnsJoinedCircle() {
        CircleNode circle = CircleNode.builder().id(7L).inviteCode("MESH-ABCD").build();
        when(circleRepository.joinCircle("u1", "MESH-ABCD")).thenReturn(Optional.of(circle));

        assertThat(circleService.joinCircle("u1", "MESH-ABCD")).isSameAs(circle);
    }

    @Test
    void joinCircle_invalidCode_throws() {
        when(circleRepository.joinCircle("u1", "BAD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> circleService.joinCircle("u1", "BAD"))
                .hasMessage("Invalid invite code or user already member");
    }

    @Test
    void addMember_existingCircle_returnsCircle() {
        CircleNode circle = CircleNode.builder().id(3L).build();
        when(circleRepository.addUserToCircle("u9", 3L)).thenReturn(Optional.of(circle));

        assertThat(circleService.addMember(3L, "u9")).isSameAs(circle);
    }

    @Test
    void addMember_repositoryReturnsEmpty_throws() {
        when(circleRepository.addUserToCircle("u9", 3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> circleService.addMember(3L, "u9"))
                .hasMessage("Failed to add user to circle");
    }

    @Test
    void getUserCircles_returnsCirclesFromRepository() {
        List<CircleNode> circles = List.of(CircleNode.builder().id(1L).build());
        when(circleRepository.findCirclesByUser("u1")).thenReturn(circles);

        assertThat(circleService.getUserCircles("u1")).isEqualTo(circles);
    }
}
