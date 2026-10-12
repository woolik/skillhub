package com.iflytek.skillhub.auth.bootstrap;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.rbac.PlatformRoleDefaults;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.namespace.GlobalNamespaceMembershipService;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;

/**
 * Resolves (or creates on first sight) the SkillHub account behind a verified
 * Dongbuddy ticket, mirroring {@code IdentityBindingService.bindOrCreate} but
 * with the account id controlled by the caller: Dongbuddy usernames
 * ({@code kb-<8hex>}) become SkillHub user ids. Like the regular OAuth flow
 * this only joins the built-in global namespace — team namespaces (e.g. pzt)
 * are assigned by admins; no personal namespace is auto-created.
 *
 * <p>Provisioning is idempotent: the (provider=dongbuddy, subject=phone)
 * binding is the stability key; repeated logins reuse the same account.
 */
@Service
public class DongbuddySsoProvisioningService {

    private final UserAccountRepository userRepo;
    private final IdentityBindingRepository bindingRepo;
    private final GlobalNamespaceMembershipService globalNamespaceMembershipService;

    public DongbuddySsoProvisioningService(UserAccountRepository userRepo,
                                           IdentityBindingRepository bindingRepo,
                                           GlobalNamespaceMembershipService globalNamespaceMembershipService) {
        this.userRepo = userRepo;
        this.bindingRepo = bindingRepo;
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
        // Same as the regular OAuth flow: join global only. Team namespaces
        // are admin-assigned; auto-creating a personal namespace produced
        // empty clutter spaces (see feat/dongbuddy-sso history).
        globalNamespaceMembershipService.ensureMember(user.getId());
        return user;
    }
}
