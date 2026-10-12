package com.iflytek.skillhub.auth.bootstrap;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.rbac.PlatformRoleDefaults;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.namespace.GlobalNamespaceMembershipService;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceService;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;

/**
 * Resolves (or creates on first sight) the SkillHub account behind a verified
 * Dongbuddy ticket, mirroring {@code IdentityBindingService.bindOrCreate} but
 * with the account id controlled by the caller: Dongbuddy usernames
 * ({@code kb-<8hex>}) become SkillHub user ids AND personal namespace slugs so
 * 「所属命名空间」 maps 1:1 to the Dongbuddy identity.
 *
 * <p>Provisioning is idempotent: the (provider=dongbuddy, subject=phone)
 * binding is the stability key; repeated logins reuse the same account and
 * never recreate the namespace.
 */
@Service
public class DongbuddySsoProvisioningService {

    private final UserAccountRepository userRepo;
    private final IdentityBindingRepository bindingRepo;
    private final NamespaceRepository namespaceRepo;
    private final NamespaceService namespaceService;
    private final GlobalNamespaceMembershipService globalNamespaceMembershipService;

    public DongbuddySsoProvisioningService(UserAccountRepository userRepo,
                                           IdentityBindingRepository bindingRepo,
                                           NamespaceRepository namespaceRepo,
                                           NamespaceService namespaceService,
                                           GlobalNamespaceMembershipService globalNamespaceMembershipService) {
        this.userRepo = userRepo;
        this.bindingRepo = bindingRepo;
        this.namespaceRepo = namespaceRepo;
        this.namespaceService = namespaceService;
        this.globalNamespaceMembershipService = globalNamespaceMembershipService;
    }

    @Transactional
    public PlatformPrincipal bindOrCreate(DongbuddySsoTicket.Payload payload) {
        UserAccount user = bindingRepo
            .findByProviderCodeAndSubject(DongbuddySsoProperties.PROVIDER_CODE, payload.phone())
            .map(binding -> userRepo.findById(binding.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                    "User not found for dongbuddy binding " + binding.getUserId())))
            .orElseGet(() -> createAccount(payload));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("error.auth.dongbuddy.accountNotActive");
        }

        return new PlatformPrincipal(
            user.getId(),
            user.getDisplayName(),
            user.getEmail(),
            user.getAvatarUrl(),
            DongbuddySsoProperties.PROVIDER_CODE,
            PlatformRoleDefaults.withDefaultUserRole(Set.of())
        );
    }

    private UserAccount createAccount(DongbuddySsoTicket.Payload payload) {
        UserAccount user = new UserAccount(payload.userId(), payload.userId(), null, null);
        user.setStatus(UserStatus.ACTIVE);
        user = userRepo.save(user);

        bindingRepo.save(new IdentityBinding(
            user.getId(),
            DongbuddySsoProperties.PROVIDER_CODE,
            payload.phone(),
            payload.userId()
        ));
        globalNamespaceMembershipService.ensureMember(user.getId());

        // Personal namespace keyed by the Dongbuddy username; createNamespace
        // grants OWNER in the same transaction. Slug collisions (a hand-made
        // namespace with the same slug) degrade gracefully to "reuse exists".
        if (namespaceRepo.findBySlug(payload.userId()).isEmpty()) {
            namespaceService.createNamespace(
                payload.userId(),
                payload.userId(),
                "Auto-provisioned Dongbuddy namespace",
                user.getId()
            );
        }
        return user;
    }
}
