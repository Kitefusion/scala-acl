# Play Acl Module

This is a module to implement a simple acl system to play framework. With this
module, you can protect controller actions, or anything else.

You can protect an admin area, or hide information / actions for specific users.
It's also possible to connect a right to specific conditions for objects.

Let's say you have a shop system and want to display orders. The orders are separated
in 2 lists (open orders and closed orders). For this case you can use the acl system
to filter entries between these 2 lists.

# How does the ACL system work

The Acl system is based on classical roles. A user can have one or more roles. Each role
have resources and privileges. A resource is a entity or an are / module to separate
rights. A privilege is the action the user want to do, like read, update, delete, expand,
manage, send, ... .

A User have roles, and will be initialized with the ACL system. An Anonymous user is also
a representation of a user, but with none or different roles.

Roles are objects, with a unique identifier which. The identifier is a bit value like
1, 2, 4, 8, 16 ...

## Define resources

A resource have to extend `org.playacl.Resource` and contain a string identifier.

```scala
object AdminResource extends org.playacl.Resource("admin")
```

## Define Privileges

A privilege have to extend `org.playacl.Privilege` and contain a string identifier.
 
```scala
object ReadPrivilege extends org.playacl.Privilege("read")
```

## Define Roles

A Role have to implement `org.playacl.Role` interface, which contains 4 abstract methods:

* `getIdentifier: Long`: the role identifier bit value
* `getRoleId: String`: returns a string identifier of this role
* `getInheritedRole: List[Role]`: a list of parent roles
* `getPrivileges: Map[Resource, Map[Privilege, Seq[Option[AclObject] => Boolean]]]`: the complete definition of rights

### Assertions

Assertions, defined in `Seq[Option[AclObject] => Boolean]` are callback functions to define
specific conditions on a resource. When you have a list of users, and want to show an edit button
to directly edit this user u can use this. An admin will have no restriction, an anonymous user
is denied for this operation, but a user can edit his own entry.

```scala
val myId = 4
case class User(id: Int) extends AclObject
// the assert definition
(user: Option[AclObject]) => user match {
	case Some(u: User) => u.exists(_.id == myId)
	case _ => false
}

acl.isAllowed(UserResource, EditPrivilege, Some(currentUser)) // returns true or false
```

This is definitly not a good way to do this, and i'm currently working on
a solution to define this in an easier way. If someone have an idea, please let me know.

## The Security Trait

For Initialization of the Acl you need to configure the Security Trait. This Trait will be used
in a controller action to initialize and retrieve the ACL instance for the current user. When there
is no user logged in, there is a guest user defined. To implement the Security trait you need to
define these methods:

* `userByUsername(username: String): Option[I]` to retrieve a user from some storage by given username
* `roles: List[Role]` the list of available roles
* `guestRole: Role` the guest role
* `guestUser: UserEntity` to return a guest / anonymous user

## Putting all Together

To configure your acl system you have to define "Resources", "Privileges", "Roles" and the "Security" trait.
I'll show a dummy implementation:

```scala
import org.playscala.{Resource, Privilege, Role, Identity}
import org.playscala.play.Security

/** Resources */
object MainResource extends Resource("main")
object UserResource extends Resource("user")
object AdminResource extends Resource("admin")

/** Privileges */
object ReadPrivilege extends Privilege("read")
object CreatePrivilege extends Privilege("create")
object LoggedInPrivilege extends Privilege("loggedIn")
object ManagePrivilege extends Privilege("manage")

/** User Roles */
object Guest extends Role {

	override def getIdentifier: Long = 1L
	override def getPrivileges: Map[Resource, Map[Privilege, Seq[(Option[AclObject]) => Boolean]]] = {
		Map(
			MainResource -> Map(
				ReadPrivilege -> Seq()
			),
			UserResource -> Map(
				ReadPrivilege -> Seq(),
				CreatePrivilege -> Seq()
			)
		)
	}
	override def getInheritedRoles: List[Role] = List()
	override def getRoleId: String = "guest"
}
object Registered extends Role {

	override def getIdentifier: Long = 2L
	override def getPrivileges: Map[Resource, Map[Privilege, Seq[(Option[AclObject]) => Boolean]]] = {
		Map(
			UserResource -> Map(
				LoggedInPrivilege -> Seq()
			)
		)
	}
	override def getInheritedRoles: List[Role] = List(Guest)
	override def getRoleId: String = "registered"
}

object Admin extends Role {
	
	override def getIdentifier: Long = 4L
	override def getPrivileges: Map[Resource, Map[Privilege, Seq[(Option[AclObject]) => Boolean]]] = {
		Map(
			AdminResource -> Map(
				ReadPrivilege -> Seq(),
				ManagePrivilege -> Seq()
			)
		)
	} 
	override def getInheritedRoles: List[Role] = List(Registered)
	override def getRoleId: String = "admin"
}

case class UserEntity(id: Int, roles: Long) extends Identity

trait Security extends net.cc.base.acl.play.Security[UserEntity, Role] {
	override def userByUsername(username: String): Option[UserEntity] = {
		UserRepository.findByUserName(username) match {
			case Success(user) => Some(user)
			case Failure(ex) => None
		}
	}
	override def roles: List[Role] = Guest :: Registered :: Admin:: Nil
	override def guestRole: Role = Guest
	override def guestUser: UserEntity = new UserEntity(0, 1L)
	override def onUnauthorized(request: RequestHeader) = Results.Redirect("/login")
}
```

# Protecting Controller instances or get ACL instance

Because of being stateless, the controller is the entry point for the ACL system and 
it will be initialized there. Also when you want to retrieve the logged in user.

The Standard implementation contains 4 Methods, which are checking if a user is logged in
and return the Acl or the user instance or even check a resource and privilege directly.

__Currently, i know that this behavior is not always useful, i will improve these methods to
be more flexible.__

* `withAuth`: check if user is logged in, otherwise `onUnauthorized` will be called
* `withUser`: check if user is logged in and provide user, otherwise `onUnauthorized` will be called
* `withAcl`: check if user is logged in and provide acl instance, otherwise `onUnauthorized` will be called
* `withProtected(r: Resource, p: Privilege)`: 
    * if user is not logged in -> `onUnauthorized` will be called
    * if acl check against resource privilege fails -> `onUnauthorized` will be called
* `withProtectedAcl(r: Resource, p: Privilege)`: 
    * if user is not logged in -> `onUnauthorized` will be called
    * if acl check against resource privilege fails -> `onUnauthorized` will be called
    * if logged in and acl check is true -> return Acl Instance
    

# Implementation in controller

To protect a controller action or retrieve Acl or current user, see the following example:

```scala
package controllers

import <your security trait>
import <your resources and privileges>
import <your user entity>
import org.playacl.Acl

/** Admin controller - we want to protected this */
class Admin @Inject()(val messagesApi: MessagesApi) extends Controller with Security {

	def dashboard = withAuth { implicit request =>
		Ok("")
	}
	
	def dashboard = withUser { implicit user: UserEntity => implicit request =>
		Ok("")
	}
	
	def dashboard = withAcl { implicit acl: Acl[UserEntity] => implicit request =>
		Ok("")
	}
	
	def dashboard = withProtected(AdminResource, ReadPrivilege) { implicit request =>
    	Ok("")
	}

	def dashboard = withProtectedAcl(AdminResource, ReadPrivilege) { implicit acl: Acl[UserEntity] => implicit request =>
    	Ok("")
	}
}
```